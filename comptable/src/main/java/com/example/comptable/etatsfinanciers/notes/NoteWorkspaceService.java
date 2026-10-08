package com.example.comptable.etatsfinanciers.notes;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteFieldDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteSectionDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NotesCatalogue;
import com.example.comptable.etatsfinanciers.service.NoteCompletionEvaluator;
import com.example.comptable.etatsfinanciers.service.NoteManualData;
import com.example.comptable.etatsfinanciers.service.NoteManualData.ManualValue;
import com.example.comptable.etatsfinanciers.service.NotesService;
import com.example.comptable.etatsfinanciers.service.NotesService.NoteGap;
import com.example.comptable.etatsfinanciers.service.NotesService.NoteResult;
import com.example.comptable.etatsfinanciers.service.NotesService.NotesResult;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
import com.example.comptable.security.UtilisateurPrincipal;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espace de travail des Notes annexes (F8.1) : donnees calculees + donnees manuelles + applicabilite + revue + audit, par exercice.
 *
 * <p>Regles : (1) un recalcul remplace {@code generatedData} et le statut, jamais {@code manualData} ni une decision humaine d'applicabilite ; (2) toute modification d'une note
 * (donnee manuelle, applicabilite) ou changement des donnees generees annule la revue : une validation obsolete n'est jamais conservee ; (3) le statut est calcule par
 * {@link NoteCompletionEvaluator}, une seule fois ; (4) lecture : tout utilisateur ayant acces a l'entreprise ; saisie et applicabilite : COLLABORATEUR ou EXPERT_COMPTABLE ; revue :
 * EXPERT_COMPTABLE ; (5) entreprise ou exercice d'un autre tenant : 404 ; (6) versionnage optimiste : {@code expectedVersion} perime = 409.</p>
 *
 * <p>Le recalcul des notes dependant d'un etat source (Compte de resultat, Bilan, TFT) a lieu a chaque lecture et a chaque finalisation : une note READY dont la source change repasse
 * INCOMPLETE, ses donnees manuelles sont conservees.</p>
 */
@Service
@RequiredArgsConstructor
public class NoteWorkspaceService {

    static final String APPLICABLE = "APPLICABLE";
    static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    static final String CANDIDATE = "NOT_APPLICABLE_CANDIDATE";
    static final String TO_DETERMINE = "TO_DETERMINE";

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private static final ObjectMapper LECTEUR = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceRepository;
    private final FinancialStatementDefinitionRegistry registry;
    private final NotesService notesService;
    private final FinancialStatementNoteRepository repository;
    private final AuditTrailService auditTrailService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------------------------------------ contrat

    public record Decision(UUID decidedBy, OffsetDateTime decidedAt, String reason, boolean humaine) {
    }

    public record Review(UUID reviewedBy, OffsetDateTime reviewedAt, boolean valid) {
    }

    public record SectionView(String sectionCode, String label, String sourceType, boolean required, String status, List<NoteCompletionEvaluator.FieldState> fields) {
    }

    /** Vue d'une note : parties generees, parties manuelles, sections manquantes, statuts, revue, version. */
    public record NoteView(String noteCode, String title, String frameworkVersion, String definitionVersion, String applicabilityStatus, Decision applicabilityDecision,
                           String completionStatus, String automationType, String readiness, String schemaStatus, boolean attached, List<String> generatedSections,
                           List<SectionView> manualSections, List<String> missingSections, List<NotesService.NoteLineResult> generatedData, List<NoteGap> gaps,
                           String manualComment, UUID preparedBy, OffsetDateTime preparedAt, Review review, long version) {
    }

    public record WorkspaceView(UUID exerciceId, String frameworkVersion, Status status, List<String> statusReasons, Map<String, Long> countsByStatus, List<NoteView> notes) {
    }

    public record FieldPatch(String sectionCode, String fieldCode, String value, String comment, String source) {
    }

    public record ManualPatch(Long expectedVersion, List<FieldPatch> fields, String manualComment) {
    }

    public record ApplicabilityPatch(Long expectedVersion, String status, String reason) {
    }

    /** Etat fige pour un snapshot : resultat du moteur + notes jointes (donnees generees, manuelles, revue) + notes non applicables tracees (non jointes). */
    public record NotesDossier(NotesResult resultat, Map<String, Object> payload) {
    }

    // ------------------------------------------------------------------------------------------------ lecture

    @Transactional
    public WorkspaceView lister(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        verifier(entrepriseId, exerciceId, principal);
        Etat etat = actualiser(entrepriseId, exerciceId, principal);
        return vue(etat);
    }

    @Transactional
    public NoteView lire(UUID entrepriseId, UUID exerciceId, String code, UtilisateurPrincipal principal) {
        verifier(entrepriseId, exerciceId, principal);
        Etat etat = actualiser(entrepriseId, exerciceId, principal);
        return noteView(etat, code);
    }

    // ------------------------------------------------------------------------------------------------ ecriture

    @Transactional
    public NoteView modifierDonneesManuelles(UUID entrepriseId, UUID exerciceId, String code, ManualPatch patch, UtilisateurPrincipal principal) {
        verifier(entrepriseId, exerciceId, principal);
        exigerSaisie(principal);
        NoteDefinition def = definition(code);
        Etat etat = actualiser(entrepriseId, exerciceId, principal);
        FinancialStatementNote ligne = etat.lignes.get(code);
        controlerVersion(ligne, patch.expectedVersion());
        if (NOT_APPLICABLE.equals(ligne.getApplicabilityStatus()) && ligne.getApplicabilityDecidedBy() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La note " + code + " est decidee non applicable : rouvrez-la avant de saisir des donnees");
        }
        if ((patch.fields() == null || patch.fields().isEmpty()) && patch.manualComment() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucune donnee a modifier");
        }
        NoteManualData avant = lireManuel(ligne);
        Map<String, Map<String, ManualValue>> sections = copie(avant);
        String now = OffsetDateTime.now().toString();
        Map<String, Object> diff = new LinkedHashMap<>();
        if (patch.fields() != null) {
            for (FieldPatch f : patch.fields()) {
                NoteSectionDefinition section = def.section(f.sectionCode());
                if (section == null || "GENERATED".equals(section.sourceType())) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Section inconnue ou non saisissable pour la note " + code + " : " + f.sectionCode());
                }
                NoteFieldDefinition champ = section.field(f.fieldCode());
                if (champ == null) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Champ inconnu " + f.sectionCode() + "." + f.fieldCode() + " pour la note " + code);
                }
                String valeur = valider(champ, f.value());
                Map<String, ManualValue> champs = sections.computeIfAbsent(section.code(), k -> new LinkedHashMap<>());
                ManualValue ancien = champs.get(champ.code());
                if (valeur == null && (f.comment() == null || f.comment().isBlank())) {
                    champs.remove(champ.code());
                } else {
                    champs.put(champ.code(), new ManualValue(valeur, f.comment(), f.source(), principal.getId(), now));
                }
                if (champs.isEmpty()) {
                    sections.remove(section.code());
                }
                diff.put(section.code() + "." + champ.code(), Map.of("avant", ancien == null || ancien.value() == null ? "" : ancien.value(), "apres", valeur == null ? "" : valeur));
            }
        }
        if (patch.manualComment() != null) {
            diff.put("manualComment", Map.of("avant", ligne.getManualComment() == null ? "" : ligne.getManualComment(), "apres", patch.manualComment()));
            ligne.setManualComment(patch.manualComment().isBlank() ? null : patch.manualComment());
        }
        ligne.setManualData(ecrire(new NoteManualData(sections)));
        ligne.setPreparedBy(principal.getId());
        ligne.setPreparedAt(OffsetDateTime.now());
        invaliderRevue(ligne, principal, "modification des donnees manuelles");
        enregistrer(ligne);
        auditer(principal, "NOTE_DONNEES_MANUELLES_MODIFIEES", entrepriseId, exerciceId, code, diff);
        return noteView(actualiser(entrepriseId, exerciceId, principal), code);
    }

    @Transactional
    public NoteView modifierApplicabilite(UUID entrepriseId, UUID exerciceId, String code, ApplicabilityPatch patch, UtilisateurPrincipal principal) {
        verifier(entrepriseId, exerciceId, principal);
        exigerSaisie(principal);
        NoteDefinition def = definition(code);
        Etat etat = actualiser(entrepriseId, exerciceId, principal);
        FinancialStatementNote ligne = etat.lignes.get(code);
        controlerVersion(ligne, patch.expectedVersion());
        String nouveau = patch.status();
        if (!List.of(APPLICABLE, NOT_APPLICABLE, TO_DETERMINE).contains(nouveau)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Statut d'applicabilite decidable : APPLICABLE, NOT_APPLICABLE ou TO_DETERMINE (annulation de la decision)");
        }
        if (NOT_APPLICABLE.equals(nouveau)) {
            NoteResult r = etat.resultat.notes().stream().filter(n -> n.code().equals(code)).findFirst().orElseThrow();
            boolean donnees = r.lines().stream().anyMatch(l -> (l.amountN() != null && l.amountN().signum() != 0) || (l.amountNMinus1() != null && l.amountNMinus1().signum() != 0));
            if (donnees) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "La note " + code + " porte des donnees comptables : elle ne peut pas etre declaree non applicable");
            }
            NoteManualData manuel = lireManuel(ligne);
            if (def.applicabilityRule() != null && "MIN_HEADCOUNT_EXCLUSIVE".equals(def.applicabilityRule().type())) {
                ManualValue v = manuel.valeur("effectif", "effectif");
                if (v != null && v.value() != null && !v.value().isBlank() && Integer.parseInt(v.value().trim()) > def.applicabilityRule().threshold()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'effectif depasse " + def.applicabilityRule().threshold() + " : la note " + code + " est applicable");
                }
            }
        }
        String avant = ligne.getApplicabilityStatus();
        if (TO_DETERMINE.equals(nouveau)) {
            ligne.setApplicabilityDecidedBy(null);
            ligne.setApplicabilityDecidedAt(null);
            ligne.setApplicabilityReason(null);
        } else {
            ligne.setApplicabilityDecidedBy(principal.getId());
            ligne.setApplicabilityDecidedAt(OffsetDateTime.now());
            ligne.setApplicabilityReason(patch.reason());
            ligne.setApplicabilityStatus(nouveau);
        }
        invaliderRevue(ligne, principal, "changement d'applicabilite");
        enregistrer(ligne);
        auditer(principal, "NOTE_APPLICABILITE_MODIFIEE", entrepriseId, exerciceId, code,
                Map.of("avant", avant, "apres", nouveau, "raison", patch.reason() == null ? "" : patch.reason()));
        return noteView(actualiser(entrepriseId, exerciceId, principal), code);
    }

    /** Validation EXPLICITE en masse des non-applicabilites proposees par le moteur : aucune note candidate ne devient jamais non applicable sans cette action. */
    @Transactional
    public WorkspaceView validerCandidates(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        verifier(entrepriseId, exerciceId, principal);
        exigerSaisie(principal);
        Etat etat = actualiser(entrepriseId, exerciceId, principal);
        List<String> validees = new ArrayList<>();
        for (FinancialStatementNote ligne : etat.lignes.values()) {
            if (CANDIDATE.equals(ligne.getApplicabilityStatus()) && ligne.getApplicabilityDecidedBy() == null) {
                ligne.setApplicabilityStatus(NOT_APPLICABLE);
                ligne.setApplicabilityDecidedBy(principal.getId());
                ligne.setApplicabilityDecidedAt(OffsetDateTime.now());
                ligne.setApplicabilityReason("validation en masse des non-applicabilites prouvees par les comptes");
                invaliderRevue(ligne, principal, "validation de non-applicabilite");
                enregistrer(ligne);
                validees.add(ligne.getNoteCode());
            }
        }
        auditer(principal, "NOTE_CANDIDATES_VALIDEES", entrepriseId, exerciceId, null, Map.of("notes", validees, "nombre", validees.size()));
        return vue(actualiser(entrepriseId, exerciceId, principal));
    }

    @Transactional
    public NoteView relire(UUID entrepriseId, UUID exerciceId, String code, Long expectedVersion, UtilisateurPrincipal principal) {
        verifier(entrepriseId, exerciceId, principal);
        exigerRevue(principal);
        definition(code);
        Etat etat = actualiser(entrepriseId, exerciceId, principal);
        FinancialStatementNote ligne = etat.lignes.get(code);
        controlerVersion(ligne, expectedVersion);
        NoteResult r = etat.resultat.notes().stream().filter(n -> n.code().equals(code)).findFirst().orElseThrow();
        if (!r.attached() || !"READY".equals(r.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une note applicable et READY peut etre revue (statut actuel : " + r.status() + ")");
        }
        ligne.setReviewedBy(principal.getId());
        ligne.setReviewedAt(OffsetDateTime.now());
        enregistrer(ligne);
        auditer(principal, "NOTE_REVUE", entrepriseId, exerciceId, code, Map.of("statut", r.status()));
        return noteView(actualiser(entrepriseId, exerciceId, principal), code);
    }

    /** Rouvre une note : annule la revue ; une non-applicabilite decidee est annulee (retour a TO_DETERMINE ou a la proposition du moteur). */
    @Transactional
    public NoteView rouvrir(UUID entrepriseId, UUID exerciceId, String code, Long expectedVersion, UtilisateurPrincipal principal) {
        verifier(entrepriseId, exerciceId, principal);
        exigerSaisie(principal);
        definition(code);
        Etat etat = actualiser(entrepriseId, exerciceId, principal);
        FinancialStatementNote ligne = etat.lignes.get(code);
        controlerVersion(ligne, expectedVersion);
        String avant = ligne.getApplicabilityStatus();
        boolean avaitRevue = ligne.getReviewedAt() != null;
        ligne.setReviewedBy(null);
        ligne.setReviewedAt(null);
        if (NOT_APPLICABLE.equals(avant) && ligne.getApplicabilityDecidedBy() != null) {
            ligne.setApplicabilityDecidedBy(null);
            ligne.setApplicabilityDecidedAt(null);
            ligne.setApplicabilityReason(null);
        }
        enregistrer(ligne);
        auditer(principal, "NOTE_ROUVERTE", entrepriseId, exerciceId, code, Map.of("applicabiliteAvant", avant, "revueAnnulee", avaitRevue));
        return noteView(actualiser(entrepriseId, exerciceId, principal), code);
    }

    // ------------------------------------------------------------------------------------------------ snapshot / findings

    /** Etat courant des notes (apres actualisation) et contenu a figer dans un snapshot : generees, manuelles, revue, versions ; les non applicables sont tracees, non jointes. */
    @Transactional
    public NotesDossier pourFinalisation(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        verifier(entrepriseId, exerciceId, principal);
        Etat etat = actualiser(entrepriseId, exerciceId, principal);
        List<Map<String, Object>> jointes = new ArrayList<>();
        List<Map<String, Object>> nonJointes = new ArrayList<>();
        for (NoteResult r : etat.resultat.notes()) {
            FinancialStatementNote ligne = etat.lignes.get(r.code());
            if (r.attached()) {
                NoteView v = noteView(etat, r.code());
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("noteCode", r.code());
                n.put("title", r.title());
                n.put("applicability", ligne.getApplicabilityStatus());
                n.put("completionStatus", ligne.getCompletionStatus());
                n.put("automationType", r.automationType());
                n.put("schemaStatus", r.schemaStatus());
                n.put("frameworkVersion", ligne.getFramework() + "/" + ligne.getDefinitionVersion());
                n.put("definitionVersion", ligne.getDefinitionVersion());
                n.put("generatedData", v.generatedData());
                n.put("manualData", lireManuel(ligne).sections());
                n.put("manualComment", ligne.getManualComment());
                n.put("review", v.review());
                n.put("preparedBy", ligne.getPreparedBy());
                n.put("preparedAt", ligne.getPreparedAt());
                jointes.add(n);
            } else {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("noteCode", r.code());
                n.put("decision", ligne.getApplicabilityStatus());
                n.put("decidedBy", ligne.getApplicabilityDecidedBy());
                n.put("decidedAt", ligne.getApplicabilityDecidedAt());
                n.put("reason", ligne.getApplicabilityReason());
                nonJointes.add(n);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("framework", etat.resultat.framework());
        payload.put("version", etat.resultat.version());
        payload.put("definitionHash", etat.resultat.definitionHash());
        payload.put("exerciseId", exerciceId);
        payload.put("status", etat.resultat.status());
        payload.put("statusReasons", etat.resultat.statusReasons());
        payload.put("notes", jointes);
        payload.put("notesNonApplicables", nonJointes);
        payload.put("controls", etat.resultat.controls());
        payload.put("generatedAt", etat.resultat.generatedAt());
        return new NotesDossier(etat.resultat, payload);
    }

    /** Resultat du moteur apres actualisation : utilise par la publication des Findings (REQUIRED_NOTE_INCOMPLETE avec champs). */
    @Transactional
    public NotesResult resultat(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        verifier(entrepriseId, exerciceId, principal);
        return actualiser(entrepriseId, exerciceId, principal).resultat;
    }

    /**
     * Idem {@link #resultat} sans utilisateur : synchronisation proactive des constats apres une operation metier. Aucun controle d'acces (l'appelant est le systeme) ; les eventuelles revues annulees
     * sont tracees comme des evenements SYSTEME.
     */
    @Transactional
    public NotesResult resultatSysteme(UUID entrepriseId, UUID exerciceId) {
        exerciceRepository.findById(exerciceId).filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable"));
        return actualiser(entrepriseId, exerciceId, null).resultat;
    }

    // ------------------------------------------------------------------------------------------------ actualisation

    private static final class Etat {
        NotesResult resultat;
        Map<String, FinancialStatementNote> lignes = new LinkedHashMap<>();
    }

    /**
     * Recalcule les notes (decisions humaines et donnees manuelles injectees dans le moteur), puis synchronise les lignes : cree les lignes manquantes, remplace les donnees generees,
     * le statut et l'applicabilite calculee. Les donnees manuelles et les decisions humaines ne sont JAMAIS modifiees ici. Une revue devenue incoherente est annulee.
     */
    private Etat actualiser(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        // Serialise les actualisations d'un meme (entreprise, exercice) pour la duree de la transaction : deux requetes simultanees (deux onglets, un chargement parallele de l'ecran) ne
        // creent plus les lignes en double (violation d'unicite) et ne se disputent plus la mise a jour des memes lignes (conflit de version) ; la seconde relit l'etat deja a jour.
        jdbcTemplate.queryForObject("select pg_advisory_xact_lock(?)", Object.class, entrepriseId.getMostSignificantBits() ^ Long.rotateLeft(exerciceId.getLeastSignificantBits(), 17));
        NotesCatalogue catalogue = registry.notes();
        List<FinancialStatementNote> existantes = repository.findByEntrepriseIdAndExerciceIdAndFrameworkAndDefinitionVersion(entrepriseId, exerciceId, catalogue.framework(),
                catalogue.version());
        Map<String, FinancialStatementNote> parCode = new HashMap<>();
        existantes.forEach(l -> parCode.put(l.getNoteCode(), l));

        Map<String, String> decisions = new HashMap<>();
        Map<String, NoteManualData> manuelles = new HashMap<>();
        for (FinancialStatementNote l : existantes) {
            if (l.getApplicabilityDecidedBy() != null && (APPLICABLE.equals(l.getApplicabilityStatus()) || NOT_APPLICABLE.equals(l.getApplicabilityStatus()))) {
                decisions.put(l.getNoteCode(), l.getApplicabilityStatus());
            }
            manuelles.put(l.getNoteCode(), lireManuel(l));
        }
        NotesResult resultat = notesService.calculer(entrepriseId, exerciceId, new NotesService.Decisions(null, decisions, false), manuelles);

        Etat etat = new Etat();
        etat.resultat = resultat;
        List<FinancialStatementNote> aSauver = new ArrayList<>();
        for (NoteResult r : resultat.notes()) {
            FinancialStatementNote ligne = parCode.get(r.code());
            boolean nouvelle = ligne == null;
            if (nouvelle) {
                ligne = new FinancialStatementNote();
                ligne.setEntrepriseId(entrepriseId);
                ligne.setExerciceId(exerciceId);
                ligne.setFramework(catalogue.framework());
                ligne.setDefinitionVersion(catalogue.version());
                ligne.setNoteCode(r.code());
            }
            boolean modifiee = nouvelle;
            String applicabilite = "UNDETERMINED".equals(r.applicability()) ? TO_DETERMINE : r.applicability();
            if (ligne.getApplicabilityDecidedBy() == null && !applicabilite.equals(ligne.getApplicabilityStatus())) {
                ligne.setApplicabilityStatus(applicabilite);
                modifiee = true;
            }
            String donnees = ecrireGenerees(r);
            boolean donneesChangees = !memeJson(donnees, ligne.getGeneratedData());
            if (donneesChangees) {
                ligne.setGeneratedData(donnees);
                modifiee = true;
            }
            if (!r.status().equals(ligne.getCompletionStatus())) {
                ligne.setCompletionStatus(r.status());
                modifiee = true;
            }
            boolean revueObsolete = ligne.getReviewedAt() != null && (donneesChangees && !nouvelle || !"READY".equals(r.status()));
            if (revueObsolete) {
                invaliderRevue(ligne, principal, "donnees generees ou statut modifies");
                modifiee = true;
            }
            etat.lignes.put(r.code(), ligne);
            if (modifiee) {
                aSauver.add(ligne);
            }
        }
        if (!aSauver.isEmpty()) {
            try {
                repository.saveAllAndFlush(aSauver);
            } catch (OptimisticLockingFailureException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Les notes ont ete modifiees par un autre utilisateur : rechargez");
            }
        }
        return etat;
    }

    private void invaliderRevue(FinancialStatementNote ligne, UtilisateurPrincipal principal, String cause) {
        if (ligne.getReviewedAt() == null) {
            return;
        }
        UUID ancienRelecteur = ligne.getReviewedBy();
        ligne.setReviewedBy(null);
        ligne.setReviewedAt(null);
        auditer(principal, "NOTE_REVUE_INVALIDEE", ligne.getEntrepriseId(), ligne.getExerciceId(), ligne.getNoteCode(),
                Map.of("cause", cause, "relecteur", ancienRelecteur == null ? "" : ancienRelecteur.toString()));
    }

    private void enregistrer(FinancialStatementNote ligne) {
        try {
            repository.saveAndFlush(ligne);
        } catch (OptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La note " + ligne.getNoteCode() + " a ete modifiee par un autre utilisateur : rechargez");
        }
    }

    private static void controlerVersion(FinancialStatementNote ligne, Long attendue) {
        if (attendue != null && attendue != ligne.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Conflit de version sur la note " + ligne.getNoteCode() + " : version courante " + ligne.getVersion() + ", version fournie " + attendue);
        }
    }

    // ------------------------------------------------------------------------------------------------ vues

    private WorkspaceView vue(Etat etat) {
        List<NoteView> notes = etat.resultat.notes().stream().map(r -> noteView(etat, r.code())).toList();
        Map<String, Long> compte = new LinkedHashMap<>();
        for (NoteView n : notes) {
            compte.merge(n.completionStatus(), 1L, Long::sum);
        }
        return new WorkspaceView(etat.resultat.exerciceId(), etat.resultat.framework() + "/" + etat.resultat.version(), etat.resultat.status(), etat.resultat.statusReasons(), compte,
                notes);
    }

    private NoteView noteView(Etat etat, String code) {
        NoteResult r = etat.resultat.notes().stream().filter(n -> n.code().equals(code)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note introuvable : " + code));
        FinancialStatementNote ligne = etat.lignes.get(code);
        List<String> generees = r.sections().stream().filter(s -> "GENERATED".equals(s.sourceType())).map(NoteCompletionEvaluator.SectionState::label).toList();
        List<SectionView> manuelles = r.sections().stream().filter(s -> !"GENERATED".equals(s.sourceType()))
                .map(s -> new SectionView(s.sectionCode(), s.label(), s.sourceType(), s.required(), s.status(), s.fields())).toList();
        boolean humaine = ligne.getApplicabilityDecidedBy() != null;
        Decision decision = new Decision(ligne.getApplicabilityDecidedBy(), ligne.getApplicabilityDecidedAt(), ligne.getApplicabilityReason(), humaine);
        Review revue = new Review(ligne.getReviewedBy(), ligne.getReviewedAt(), ligne.getReviewedAt() != null);
        return new NoteView(code, r.title(), ligne.getFramework() + "/" + ligne.getDefinitionVersion(), ligne.getDefinitionVersion(), ligne.getApplicabilityStatus(), decision,
                r.status(), r.automationType(), r.readiness(), r.schemaStatus(), r.attached(), generees, manuelles, r.missingSections(), r.lines(), r.gaps(), ligne.getManualComment(),
                ligne.getPreparedBy(), ligne.getPreparedAt(), revue, ligne.getVersion());
    }

    // ------------------------------------------------------------------------------------------------ validation et serialisation

    private static String valider(NoteFieldDefinition champ, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        String v = valeur.trim();
        try {
            switch (champ.type()) {
                case "TEXT" -> {
                }
                case "DECIMAL" -> new BigDecimal(v);
                case "INTEGER" -> Integer.parseInt(v);
                case "DATE" -> LocalDate.parse(v);
                case "BOOLEAN" -> {
                    if (!v.equals("true") && !v.equals("false")) {
                        throw new IllegalArgumentException("booleen attendu");
                    }
                }
                case "ENUM" -> {
                    if (!champ.options().contains(v)) {
                        throw new IllegalArgumentException("valeur hors liste : " + champ.options());
                    }
                }
                default -> throw new IllegalArgumentException("type de champ non supporte : " + champ.type());
            }
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Valeur invalide pour " + champ.code() + " (" + champ.type() + ") : " + e.getMessage());
        }
        return v;
    }

    private static Map<String, Map<String, ManualValue>> copie(NoteManualData m) {
        Map<String, Map<String, ManualValue>> c = new LinkedHashMap<>();
        m.sections().forEach((k, v) -> c.put(k, new LinkedHashMap<>(v)));
        return c;
    }

    static NoteManualData lireManuel(FinancialStatementNote ligne) {
        try {
            return ligne.getManualData() == null ? NoteManualData.vide() : MAPPER.readValue(ligne.getManualData(), NoteManualData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Donnees manuelles illisibles pour la note " + ligne.getNoteCode(), e);
        }
    }

    private static String ecrire(NoteManualData manuel) {
        try {
            return MAPPER.writeValueAsString(manuel);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ecrireGenerees(NoteResult r) {
        try {
            Map<String, Object> donnees = new LinkedHashMap<>();
            donnees.put("lines", r.lines());
            donnees.put("schemaStatus", r.schemaStatus());
            return MAPPER.writeValueAsString(donnees);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Egalite semantique de deux JSON (ordre des cles et echelle des decimaux ignores : le jsonb de la base normalise les deux). */
    private static boolean memeJson(String ecrit, String relu) {
        if (relu == null) {
            return false;
        }
        try {
            return LECTEUR.readTree(ecrit).equals(LECTEUR.readTree(relu));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ garde-fous

    private void verifier(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        exerciceRepository.findById(exerciceId).filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable"));
    }

    private NoteDefinition definition(String code) {
        return registry.notes().notes().stream().filter(n -> n.code().equals(code)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note introuvable : " + code));
    }

    private static void exigerSaisie(UtilisateurPrincipal principal) {
        if (!aRole(principal, "ROLE_COLLABORATEUR") && !aRole(principal, "ROLE_EXPERT_COMPTABLE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Reserve aux collaborateurs et experts-comptables du cabinet");
        }
    }

    private static void exigerRevue(UtilisateurPrincipal principal) {
        if (!aRole(principal, "ROLE_EXPERT_COMPTABLE")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La revue d'une note est reservee a l'expert-comptable");
        }
    }

    private static boolean aRole(UtilisateurPrincipal principal, String role) {
        return principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(role));
    }

    private void auditer(UtilisateurPrincipal principal, String action, UUID entrepriseId, UUID exerciceId, String code, Map<String, Object> details) {
        Map<String, Object> d = new LinkedHashMap<>(details);
        d.put("entreprise", entrepriseId.toString());
        d.put("exercice", exerciceId.toString());
        if (code != null) {
            d.put("noteCode", code);
        }
        String entite = code == null ? exerciceId.toString() : exerciceId + ":" + code;
        if (principal == null) {
            UUID tenantId = jdbcTemplate.queryForObject("select tenant_id from entreprise where id = ?", UUID.class, entrepriseId);
            auditTrailService.enregistrerEvenementSysteme(tenantId, action, "NOTE_ANNEXE", entite, d);
            return;
        }
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), action, "NOTE_ANNEXE", entite, d);
    }
}
