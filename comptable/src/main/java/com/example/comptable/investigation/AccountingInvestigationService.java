package com.example.comptable.investigation;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.banque.BankInternalTransferRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.controle.ControlResultAdapters;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.investigation.InvestigationResult.Basis;
import com.example.comptable.investigation.InvestigationResult.Cause;
import com.example.comptable.investigation.InvestigationResult.Nature;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Accounting Debugger : « pourquoi ce solde, cet ecart, cette alerte ? ». Depuis un constat, un tiers, une facture, une ecriture, une
 * transaction, un releve, un document ou une immobilisation, reconstitue source -> evenements -> consequences comptables -> rapprochements ->
 * affectations -> solde -> anomalie.
 *
 * <p><b>Il ne corrige jamais.</b> Aucune methode ne cree d'ecriture, ne modifie un compte, ne supprime une affectation, ne force un rapprochement,
 * ne change un solde : les actions suggerees nomment une operation des services metier existants, que le comptable declenche lui-meme. Tout est en
 * lecture seule, recalcule a la demande, sans nouvelle table.</p>
 */
@Service
public class AccountingInvestigationService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final FindingRepository findingRepository;
    private final BankInternalTransferRepository transferRepository;
    private final EcritureComptableRepository ecritureRepository;
    private final TiersInvestigator tiersInvestigator;
    private final BankInvestigator bankInvestigator;
    private final PieceInvestigator pieceInvestigator;
    private final com.example.comptable.audit.AuditTrailService auditTrailService;

    public AccountingInvestigationService(EntrepriseAccessService entrepriseAccessService, FindingRepository findingRepository,
                                          BankInternalTransferRepository transferRepository, EcritureComptableRepository ecritureRepository,
                                          TiersInvestigator tiersInvestigator, BankInvestigator bankInvestigator, PieceInvestigator pieceInvestigator,
                                          com.example.comptable.audit.AuditTrailService auditTrailService) {
        this.auditTrailService = auditTrailService;
        this.entrepriseAccessService = entrepriseAccessService;
        this.findingRepository = findingRepository;
        this.transferRepository = transferRepository;
        this.ecritureRepository = ecritureRepository;
        this.tiersInvestigator = tiersInvestigator;
        this.bankInvestigator = bankInvestigator;
        this.pieceInvestigator = pieceInvestigator;
    }

    /** Investigation d'un objet par son type (TIERS, LIGNE_ECRITURE, ECRITURE, FACTURE, LIGNE_RELEVE, RELEVE, DOCUMENT, IMMOBILISATION). */
    @Transactional(readOnly = true)
    public InvestigationResult investiguer(UUID entrepriseId, String type, UUID id, LocalDate arrete, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return construire(entrepriseId, type, id, arrete, principal).build();
    }

    /** Investigation contextuelle d'un constat : elle se concentre sur ce que le constat designe (transaction, reglement, tiers, piece...). */
    @Transactional
    public InvestigationResult depuisConstat(UUID entrepriseId, UUID findingId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Finding f = findingRepository.findById(findingId).filter(x -> x.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Constat introuvable"));
        String type = f.getSourceType() == null ? "" : f.getSourceType().toUpperCase(Locale.ROOT);
        InvestigationResult.Builder b;
        UUID id = idDe(f.getSourceId());
        if (id == null) {
            b = generique(entrepriseId, f);
        } else if ("BANK_INTERNAL_TRANSFER".equals(type)) {
            var t = transferRepository.findByIdAndEntrepriseId(id, entrepriseId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Virement introuvable"));
            b = bankInvestigator.transaction(entrepriseId, t.getDebitTransactionId());
        } else if (List.of("TIERS", "LIGNE_ECRITURE", "ECRITURE_COMPTABLE", "ECRITURE", "FACTURE", "LIGNE_RELEVE", "RELEVE", "DOCUMENT", "IMMOBILISATION").contains(type)) {
            b = construire(entrepriseId, type, id, null, principal);
        } else {
            b = generique(entrepriseId, f);
        }
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "INVESTIGATION_OUVERTE", "FINDING", findingId.toString(),
                Map.of("code", String.valueOf(f.getControlCode())));
        var resultat = ControlResultAdapters.depuisFinding(f);
        Map<String, Object> constat = new LinkedHashMap<>();
        constat.put("id", f.getId().toString());
        constat.put("code", f.getControlCode());
        constat.put("severite", f.getControlSeverity().name());
        constat.put("statut", f.getStatus().name());
        constat.put("titre", f.getTitle());
        constat.put("bloquant", resultat.blocking());
        b.detail("constat", constat);
        return b.build();
    }

    private InvestigationResult.Builder construire(UUID entrepriseId, String type, UUID id, LocalDate arrete, UtilisateurPrincipal principal) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "TIERS" -> tiersInvestigator.investiguerTiers(entrepriseId, id, arrete);
            case "LIGNE_ECRITURE" -> tiersInvestigator.investiguerLigne(entrepriseId, id);
            case "ECRITURE", "ECRITURE_COMPTABLE" -> pieceInvestigator.ecriture(entrepriseId, id, principal);
            case "FACTURE" -> pieceInvestigator.facture(entrepriseId, id, principal);
            case "LIGNE_RELEVE", "TRANSACTION" -> bankInvestigator.transaction(entrepriseId, id);
            case "RELEVE" -> bankInvestigator.releve(entrepriseId, id);
            case "DOCUMENT" -> pieceInvestigator.document(entrepriseId, id);
            case "IMMOBILISATION" -> pieceInvestigator.immobilisation(entrepriseId, id);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type d'objet non investigable : " + type);
        };
    }

    /**
     * Causes d'un releve pour la pre-cloture (lecture interne, l'acces a l'entreprise est deja verifie par l'appelant) : la meme reconstitution
     * que « Pourquoi ? », resumee en {@code [{code, nature, montant, explication}]}.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> causesDuReleve(UUID entrepriseId, UUID releveId) {
        List<Map<String, Object>> resultat = new java.util.ArrayList<>();
        for (Cause c : bankInvestigator.releve(entrepriseId, releveId).build().causes()) {
            if (c.nature() == Nature.INFO) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code());
            m.put("nature", c.nature().name());
            m.put("montant", c.amount());
            m.put("explication", c.explanation());
            resultat.add(m);
        }
        return resultat;
    }

    /** Constat agrege ou sans objet unique (resume par releve, par entreprise...) : on le decrit et on rend navigables les objets qu'il cite. */
    private InvestigationResult.Builder generique(UUID entrepriseId, Finding f) {
        InvestigationResult.Builder b = new InvestigationResult.Builder(f.getSourceType() == null ? "CONSTAT" : f.getSourceType(), f.getSourceId(), f.getTitle());
        var resultat = ControlResultAdapters.depuisFinding(f);
        List<UUID> ids = resultat.relatedEntityIds().stream().map(AccountingInvestigationService::idDe).filter(java.util.Objects::nonNull).limit(BankInvestigator.EXEMPLES).toList();
        String code = f.getControlCode();
        String typeLie = "MISSING_DOCUMENT".equals(code) ? "ECRITURE" : code != null && code.startsWith("DOCUMENT") ? "DOCUMENT" : "LIGNE_ECRITURE";
        if ("ECRITURE".equals(typeLie) && !ids.isEmpty()) {
            ecritureRepository.findAllById(ids).stream().filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                    .forEach(e -> b.related("ECRITURE", e.getId().toString(), "Ecriture " + e.getNumeroPiece() + " : " + e.getLibelle()));
        } else {
            ids.forEach(i -> b.related(typeLie, i.toString(), "Objet concerne"));
        }
        b.cause(new Cause(code == null ? "FINDING" : code, Nature.ANOMALY, f.getDescription() == null ? f.getTitle() : f.getDescription(), Basis.PERSISTED, montantDe(resultat.metadata()), List.of()));
        if (f.getRecommendedAction() != null) {
            b.action("RECOMMENDED", f.getRecommendedAction(), "Revue", f.getSourceType(), f.getSourceId());
        }
        b.summary(f.getTitle() + " : constat agrege, ouvrez l'un des objets cites pour le detail.");
        return b;
    }

    private static BigDecimal montantDe(Map<String, Object> metadata) {
        for (String cle : List.of("montantTotal", "montantNet", "montantNonAffecte", "montant")) {
            if (metadata.get(cle) instanceof BigDecimal m) {
                return m;
            }
            if (metadata.get(cle) instanceof Number n) {
                return new BigDecimal(n.toString());
            }
        }
        return null;
    }

    private static UUID idDe(String texte) {
        if (texte == null) {
            return null;
        }
        try {
            return UUID.fromString(texte);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
