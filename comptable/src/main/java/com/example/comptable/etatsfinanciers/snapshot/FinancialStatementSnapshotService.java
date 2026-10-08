package com.example.comptable.etatsfinanciers.snapshot;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService;
import com.example.comptable.etatsfinanciers.service.CashFlowStatementService;
import com.example.comptable.etatsfinanciers.service.IncomeStatementService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService;
import com.example.comptable.etatsfinanciers.service.StatementResult;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
import com.example.comptable.security.UtilisateurPrincipal;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finalisation des etats financiers (F11, G8). Un etat n'est FINAL que s'il est complet, coherent et verifie : {@code READY} ET exercice CLOTURE. Un etat {@code INCOMPLETE} n'est
 * JAMAIS finalise (rien n'est masque, pas de FINAL partiel). Le snapshot fige entreprise, exercice, version reglementaire (cadre, version, empreinte de definition), montants,
 * comptes par ligne, controles et empreinte des donnees ; il est immuable. {@link #finaliserDossier} fige en un seul snapshot le dossier complet (Compte de resultat, Bilan, TFT, Notes
 * applicables, controles) avec les empreintes de toutes les definitions, et refuse tout dossier dont un element n'est pas {@code READY}.
 */
@Service
@RequiredArgsConstructor
public class FinancialStatementSnapshotService {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceRepository;
    private final IncomeStatementService incomeStatementService;
    private final BalanceSheetService balanceSheetService;
    private final CashFlowStatementService cashFlowStatementService;
    private final NoteWorkspaceService noteWorkspaceService;
    private final FinancialStatementSnapshotRepository snapshotRepository;
    private final AuditTrailService auditTrailService;

    public record SnapshotDto(UUID id, UUID exerciceId, StatementType statementType, String framework, String definitionVersion, String definitionHash, String status,
                              String datasetFingerprint, String payloadSha256, OffsetDateTime createdAt, JsonNode payload) {
    }

    @Transactional
    public SnapshotDto finaliser(UUID entrepriseId, UUID exerciceId, StatementType type, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        com.example.comptable.etatsfinanciers.dossier.DossierRoles.exigerFinalisation(principal);
        ExerciceComptable exercice = exerciceRepository.findById(exerciceId).filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable"));

        if (type == StatementType.DOSSIER_COMPLET) {
            return finaliserDossier(entrepriseId, exercice, principal);
        }
        if (type == StatementType.NOTES_R4) {
            return figerNotes(entrepriseId, exercice, noteWorkspaceService.pourFinalisation(entrepriseId, exerciceId, principal), principal);
        }
        StatementResult etat = switch (type) {
            case COMPTE_RESULTAT -> incomeStatementService.generer(entrepriseId, exerciceId, principal);
            case BILAN_ACTIF -> balanceSheetService.actif(entrepriseId, exerciceId, principal);
            case BILAN_PASSIF -> balanceSheetService.passif(entrepriseId, exerciceId, principal);
            case TFT -> cashFlowStatementService.generer(entrepriseId, exerciceId, principal);
            default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cet etat ne se finalise pas");
        };
        if (etat.status() != Status.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "L'etat " + type + " est " + etat.status() + " et ne peut pas etre finalise : " + String.join(" ; ", etat.statusReasons()));
        }
        return figer(entrepriseId, exercice, etat, principal);
    }

    /**
     * Fige un etat deja reconnu {@code READY} : exercice CLOTURE, aucun snapshot existant pour cette version reglementaire, persistance immuable, trace d'audit.
     * Appelee par {@link #finaliser} apres le controle de completude ; separee pour que ces gardes soient testables independamment de l'etat des donnees.
     */
    @Transactional
    public SnapshotDto figer(UUID entrepriseId, ExerciceComptable exercice, StatementResult etat, UtilisateurPrincipal principal) {
        verifierFigeable(entrepriseId, exercice, etat.statementType(), etat.framework(), etat.version());
        return persister(entrepriseId, exercice, etat.statementType(), etat.framework(), etat.version(), etat.definitionHash(), etat.dataset().fingerprint(),
                etat.avecStatut(Status.FINAL), principal);
    }

    /** Fige les Notes : uniquement si leur statut est {@code READY} (aucune note applicable incomplete, aucune applicabilite a determiner). */
    @Transactional
    public SnapshotDto figerNotes(UUID entrepriseId, ExerciceComptable exercice, NoteWorkspaceService.NotesDossier dossierNotes, UtilisateurPrincipal principal) {
        var notes = dossierNotes.resultat();
        if (notes.status() != Status.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Les Notes sont " + notes.status() + " et ne peuvent pas etre finalisees : " + String.join(" ; ", notes.statusReasons()));
        }
        verifierFigeable(entrepriseId, exercice, StatementType.NOTES_R4, notes.framework(), notes.version());
        return persister(entrepriseId, exercice, StatementType.NOTES_R4, notes.framework(), notes.version(), notes.definitionHash(), "", figee(dossierNotes), principal);
    }

    /** Contenu d'un dossier (apercu ou final) : charge utile, motifs de refus de finalisation et etats calcules. */
    public record ContenuDossier(Map<String, Object> payload, List<String> refus, StatementResult cr, BalanceSheetService.BilanResult bilan, StatementResult tft,
                                 NoteWorkspaceService.NotesDossier notes) {
    }

    /**
     * Construit le contenu du dossier complet : identite, cadre et version, empreintes de toutes les definitions, exercice, date de generation, Compte de resultat, Bilan (Actif, Passif),
     * TFT, Notes applicables (generees, manuelles, revue), notes non applicables tracees et controles. {@code statut} : {@code FINAL} (snapshot) ou {@code BROUILLON} (apercu, export).
     */
    @Transactional
    public ContenuDossier construire(UUID entrepriseId, ExerciceComptable exercice, String statut, UtilisateurPrincipal principal) {
        UUID exerciceId = exercice.getId();
        var entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        BalanceSheetService.BilanResult bilan = balanceSheetService.bilan(entrepriseId, exerciceId, principal);
        StatementResult cr = incomeStatementService.generer(entrepriseId, exerciceId, principal);
        StatementResult tft = cashFlowStatementService.generer(entrepriseId, exerciceId, principal);
        NoteWorkspaceService.NotesDossier dossierNotes = noteWorkspaceService.pourFinalisation(entrepriseId, exerciceId, principal);
        var notes = dossierNotes.resultat();
        List<String> refus = new ArrayList<>();
        if (cr.status() != Status.READY) {
            refus.add("Compte de resultat " + cr.status());
        }
        if (bilan.status() != Status.READY) {
            refus.add("Bilan " + bilan.status());
        }
        if (tft.status() != Status.READY) {
            refus.add("TFT " + tft.status());
        }
        if (notes.status() != Status.READY) {
            refus.add("Notes " + notes.status());
        }
        boolean fige = "FINAL".equals(statut);
        Map<String, String> empreintes = new LinkedHashMap<>();
        empreintes.put(StatementType.COMPTE_RESULTAT.name(), cr.definitionHash());
        empreintes.put(StatementType.BILAN_ACTIF.name(), bilan.actif().definitionHash());
        empreintes.put(StatementType.BILAN_PASSIF.name(), bilan.passif().definitionHash());
        empreintes.put(StatementType.TFT.name(), tft.definitionHash());
        empreintes.put(StatementType.NOTES_R4.name(), notes.definitionHash());
        List<Object> controles = new ArrayList<>();
        controles.addAll(cr.controls());
        controles.addAll(bilan.controls());
        controles.addAll(tft.controls());
        controles.addAll(notes.controls());

        Map<String, Object> identite = new LinkedHashMap<>();
        identite.put("id", entreprise.getId());
        identite.put("raisonSociale", entreprise.getRaisonSociale());
        identite.put("numeroContribuable", entreprise.getNumeroContribuable());
        identite.put("pays", entreprise.getPays());
        identite.put("devise", entreprise.getDevise());
        Map<String, Object> periode = new LinkedHashMap<>();
        periode.put("id", exerciceId);
        periode.put("debut", exercice.getDateDebut());
        periode.put("fin", exercice.getDateFin());
        periode.put("statut", exercice.getStatut());

        Map<String, Object> dossier = new LinkedHashMap<>();
        dossier.put("frameworkVersion", cr.framework() + "/" + cr.version());
        dossier.put("definitionHashes", empreintes);
        dossier.put("entreprise", identite);
        dossier.put("exercice", periode);
        dossier.put("exerciseId", exerciceId);
        dossier.put("generatedAt", OffsetDateTime.now());
        dossier.put("status", statut);
        dossier.put("compteResultat", fige ? cr.avecStatut(Status.FINAL) : cr);
        dossier.put("bilanActif", fige ? bilan.actif().avecStatut(Status.FINAL) : bilan.actif());
        dossier.put("bilanPassif", fige ? bilan.passif().avecStatut(Status.FINAL) : bilan.passif());
        dossier.put("tft", fige ? tft.avecStatut(Status.FINAL) : tft);
        dossier.put("notes", fige ? figee(dossierNotes) : new LinkedHashMap<>(dossierNotes.payload()));
        dossier.put("controls", controles);
        return new ContenuDossier(dossier, refus, cr, bilan, tft, dossierNotes);
    }

    /** Apercu BROUILLON du dossier complet (aucune persistance) : jamais presente comme final. */
    @Transactional
    public Map<String, Object> apercu(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = exerciceRepository.findById(exerciceId).filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable"));
        return construire(entrepriseId, exercice, "BROUILLON", principal).payload();
    }

    /**
     * Fige le dossier complet. Refuse tout dossier dont un element n'est pas {@code READY} ou dont l'exercice n'est pas cloture ; reserve a l'expert-comptable.
     */
    @Transactional
    public SnapshotDto finaliserDossier(UUID entrepriseId, ExerciceComptable exercice, UtilisateurPrincipal principal) {
        ContenuDossier contenu = construire(entrepriseId, exercice, "FINAL", principal);
        if (!contenu.refus().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le dossier ne peut pas etre finalise : " + String.join(", ", contenu.refus()));
        }
        StatementResult cr = contenu.cr();
        verifierFigeable(entrepriseId, exercice, StatementType.DOSSIER_COMPLET, cr.framework(), cr.version());
        @SuppressWarnings("unchecked")
        Map<String, String> empreintes = (Map<String, String>) contenu.payload().get("definitionHashes");
        return persister(entrepriseId, exercice, StatementType.DOSSIER_COMPLET, cr.framework(), cr.version(), sha256(String.join("|", empreintes.values())),
                cr.dataset().fingerprint(), contenu.payload(), principal);
    }

    /** Contenu fige des notes : donnees generees et manuelles copiees (jamais une reference vers l'etat vivant), revue, versions ; statut FINAL. */
    private static Map<String, Object> figee(NoteWorkspaceService.NotesDossier dossierNotes) {
        Map<String, Object> payload = new LinkedHashMap<>(dossierNotes.payload());
        payload.put("status", Status.FINAL);
        return payload;
    }

    private void verifierFigeable(UUID entrepriseId, ExerciceComptable exercice, StatementType type, String framework, String version) {
        if (exercice.getStatut() != StatutExercice.CLOTURE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "L'exercice doit etre cloture avant la finalisation de l'etat " + type);
        }
        if (snapshotRepository.findByEntrepriseIdAndExerciceIdAndStatementTypeAndFrameworkAndDefinitionVersion(entrepriseId, exercice.getId(), type, framework, version).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet etat est deja finalise pour cette version reglementaire (immuable)");
        }
    }

    private SnapshotDto persister(UUID entrepriseId, ExerciceComptable exercice, StatementType type, String framework, String version, String definitionHash,
                                  String datasetFingerprint, Object contenu, UtilisateurPrincipal principal) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(contenu);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialisation du snapshot impossible", e);
        }
        FinancialStatementSnapshot snapshot = new FinancialStatementSnapshot();
        snapshot.setEntrepriseId(entrepriseId);
        snapshot.setExerciceId(exercice.getId());
        snapshot.setStatementType(type);
        snapshot.setFramework(framework);
        snapshot.setDefinitionVersion(version);
        snapshot.setDefinitionHash(definitionHash);
        snapshot.setDatasetFingerprint(datasetFingerprint);
        snapshot.setPayload(payload);
        snapshot.setPayloadSha256(sha256(payload));
        snapshot.setCreatedBy(principal.getId());
        snapshot.setCreatedAt(OffsetDateTime.now());
        snapshot = snapshotRepository.save(snapshot);

        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "ETAT_FINANCIER_FINALISE", "ETAT_FINANCIER", snapshot.getId().toString(),
                Map.of("etat", type.name(), "exercice", exercice.getDateDebut() + "/" + exercice.getDateFin(), "cadre", framework, "version", version,
                        "empreinteDefinition", definitionHash, "empreinteDonnees", datasetFingerprint));
        return versDto(snapshot);
    }

    @Transactional(readOnly = true)
    public List<SnapshotDto> lister(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return snapshotRepository.findByEntrepriseIdAndExerciceIdOrderByCreatedAtDesc(entrepriseId, exerciceId).stream().map(this::versDto).toList();
    }

    /** Relit l'etat fige tel quel (jamais recalcule). Autre entreprise : 404. */
    @Transactional(readOnly = true)
    public SnapshotDto lire(UUID entrepriseId, UUID snapshotId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return snapshotRepository.findByIdAndEntrepriseId(snapshotId, entrepriseId).map(this::versDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Etat financier introuvable"));
    }

    private SnapshotDto versDto(FinancialStatementSnapshot s) {
        try {
            return new SnapshotDto(s.getId(), s.getExerciceId(), s.getStatementType(), s.getFramework(), s.getDefinitionVersion(), s.getDefinitionHash(), s.getStatus(),
                    s.getDatasetFingerprint(), s.getPayloadSha256(), s.getCreatedAt(), MAPPER.readTree(s.getPayload()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String texte) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texte.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
