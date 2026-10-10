package com.example.comptable.copilot;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.finding.web.dto.FindingDto;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.copilot.domain.IntentCopilot;
import com.example.comptable.copilot.web.dto.CopilotResponseDto;
import com.example.comptable.copilot.web.dto.EvidenceRefDto;
import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.DossierComptableService;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.CashPositionService;
import com.example.comptable.finance.web.dto.CashPositionDto;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Evidence-First Accounting Copilot" (mission P1, sections 14-19, 42-48), niveau comptable.
 * Pipeline obligatoire (section 17, meme discipline que {@code agent.matching.matching_engine}
 * cote Python) :
 * <pre>question -&gt; classification (Java, deterministe) -&gt; donnees reelles (Java) -&gt; LLM pour la
 * mise en mots uniquement -&gt; reponse structuree avec preuves</pre>
 * Le Copilot ne calcule jamais rien lui-meme : chaque intention reutilise un service/repository
 * P0/P1 deja existant (jamais une deuxieme implementation de la meme logique). Strictement
 * lecture seule - aucune methode ici ne modifie d'etat.
 */
@Service
public class CopilotService {

    private static final int MAX_ITEMS_PREUVE = 5;

    /** Mission P1.5 (section 46, "Copilot Dirigeant") : sous-ensemble d'intentions dont le
     * vocabulaire reste comprehensible sans formation comptable - exclut ANOMALIES_FOURNISSEURS
     * ("anomalie de document") et EVOLUTION_TVA (comparaison de declarations fiscales), qui
     * supposent un vocabulaire d'expert-comptable. Applique meme si l'intention est detectable
     * par mots-cles dans la question libre du dirigeant (jamais uniquement un filtre cote UI). */
    private static final Set<IntentCopilot> INTENTS_DIRIGEANT = EnumSet.of(
            IntentCopilot.POURQUOI_DOSSIER_PAS_PRET,
            IntentCopilot.BLOQUANTS_CLOTURE,
            IntentCopilot.ECART_BANQUE,
            IntentCopilot.PIECES_MANQUANTES,
            IntentCopilot.RETARDS_CLIENTS,
            IntentCopilot.A_TRAITER_MAINTENANT);

    private final EntrepriseAccessService entrepriseAccessService;
    private final DossierComptableService dossierComptableService;
    private final ClotureReadinessService clotureReadinessService;
    private final CashPositionService cashPositionService;
    private final FindingRepository findingRepository;
    private final FactureRepository factureRepository;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;
    private final CopilotExplainer copilotExplainer;
    private final CopilotTelemetryRecorder telemetryRecorder;
    private final com.example.comptable.comptabilite.allocation.StatutPaiementFactureService statutPaiementFactureService;

    public CopilotService(EntrepriseAccessService entrepriseAccessService,
                           DossierComptableService dossierComptableService,
                           ClotureReadinessService clotureReadinessService,
                           CashPositionService cashPositionService,
                           FindingRepository findingRepository,
                           FactureRepository factureRepository,
                           DeclarationFiscaleRepository declarationFiscaleRepository,
                           CopilotExplainer copilotExplainer,
                           CopilotTelemetryRecorder telemetryRecorder,
                           com.example.comptable.comptabilite.allocation.StatutPaiementFactureService statutPaiementFactureService) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.dossierComptableService = dossierComptableService;
        this.clotureReadinessService = clotureReadinessService;
        this.cashPositionService = cashPositionService;
        this.findingRepository = findingRepository;
        this.factureRepository = factureRepository;
        this.declarationFiscaleRepository = declarationFiscaleRepository;
        this.copilotExplainer = copilotExplainer;
        this.telemetryRecorder = telemetryRecorder;
        this.statutPaiementFactureService = statutPaiementFactureService;
    }

    @Transactional(readOnly = true)
    public CopilotResponseDto repondre(UUID entrepriseId, String question, UtilisateurPrincipal principal) {
        // RBAC/isolation AVANT toute chose, y compris avant la classification (mission section 43 :
        // "le filtrage doit etre fait avant l'appel LLM").
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        long debut = System.currentTimeMillis();
        IntentCopilot intent = IntentClassifier.classifier(question);
        if (intent == IntentCopilot.INCONNU) {
            CopilotResponseDto reponse = reponseIntentInconnue();
            UUID queryId = enregistrerTelemetrie(entrepriseId, principal, "COMPTABLE", question, "INCONNU", reponse,
                    System.currentTimeMillis() - debut);
            return avecQueryId(reponse, queryId);
        }

        FactsEtPreuves donnees = collecter(intent, entrepriseId, principal);
        String explication = copilotExplainer.expliquer(question, intent.name(), donnees.facts());

        CopilotResponseDto reponse = new CopilotResponseDto(explication, donnees.confidence(), donnees.evidence(), donnees.recommendedActions(), null);
        UUID queryId = enregistrerTelemetrie(entrepriseId, principal, "COMPTABLE", question, intent.name(), reponse,
                System.currentTimeMillis() - debut);
        return avecQueryId(reponse, queryId);
    }

    private FactsEtPreuves collecter(IntentCopilot intent, UUID entrepriseId, UtilisateurPrincipal principal) {
        return switch (intent) {
            case POURQUOI_DOSSIER_PAS_PRET -> collecterDossier(entrepriseId, principal);
            case BLOQUANTS_CLOTURE -> collecterCloture(entrepriseId, principal);
            case ECART_BANQUE -> collecterEcartBanque(entrepriseId, principal);
            case PIECES_MANQUANTES -> collecterFindingsParType(entrepriseId, TypeFinding.MISSING_EVIDENCE);
            case ANOMALIES_FOURNISSEURS -> collecterFindingsParType(entrepriseId, TypeFinding.DOCUMENT_REVIEW);
            case RETARDS_CLIENTS -> collecterRetardsClients(entrepriseId);
            case EVOLUTION_TVA -> collecterEvolutionTva(entrepriseId);
            case A_TRAITER_MAINTENANT -> collecterATraiterMaintenant(entrepriseId, principal);
            case INCONNU -> throw new IllegalStateException("INCONNU deja intercepte avant collecte");
        };
    }

    private FactsEtPreuves collecterDossier(UUID entrepriseId, UtilisateurPrincipal principal) {
        DossierComptableDto dossier = dossierComptableService.generer(entrepriseId, principal);
        Map<String, Object> facts = new LinkedHashMap<>();
        if (dossier.aucuneActivite()) {
            // rien n'est enregistré : ne jamais présenter un score ou un statut « prêt » (voir DossierComptableService)
            facts.put("aucuneActivite", true);
        } else {
            facts.put("scoreGlobal", dossier.scoreGlobal());
            facts.put("statut", dossier.statut());
            dossier.scoresParDomaine().forEach(s -> facts.put("score_" + s.domaine(), s.score()));
        }

        List<FindingDto> top = dossier.actionsPrioritaires().stream().limit(MAX_ITEMS_PREUVE).toList();
        facts.put("nombreActionsPrioritaires", dossier.actionsPrioritaires().size());
        List<EvidenceRefDto> evidence = top.stream()
                .map(f -> new EvidenceRefDto(f.sourceType(), f.sourceId()))
                .toList();
        List<String> actions = top.stream().map(FindingDto::title).toList();

        return new FactsEtPreuves(facts, evidence, actions, dossier.actionsPrioritaires().isEmpty() ? 0.95 : 0.9);
    }

    private FactsEtPreuves collecterCloture(UUID entrepriseId, UtilisateurPrincipal principal) {
        ClotureReadinessDto readiness = clotureReadinessService.evaluer(entrepriseId, principal);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("statut", readiness.statut());
        facts.put("bloquants", readiness.blockingItems());
        facts.put("avertissements", readiness.warnings());

        // Pas d'identifiant stable par item (ClotureReadinessDto porte des chaines libres, pas de
        // reference Finding) - ancre sur l'entreprise elle-meme, limite documentee (voir
        // docs/P1_ARCHITECTURE.md).
        List<EvidenceRefDto> evidence = readiness.blockingItems().isEmpty() ? List.of()
                : List.of(new EvidenceRefDto("CLOTURE_READINESS", entrepriseId.toString()));

        return new FactsEtPreuves(facts, evidence, readiness.recommendations(),
                readiness.blockingItems().isEmpty() ? 0.95 : 0.85);
    }

    private FactsEtPreuves collecterEcartBanque(UUID entrepriseId, UtilisateurPrincipal principal) {
        CashPositionDto position = cashPositionService.generer(entrepriseId, LocalDate.now(), principal);
        List<Finding> lignesNonRapprochees = findingRepository
                .findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN)
                .stream()
                .filter(f -> "LIGNE_RELEVE".equals(f.getSourceType()))
                .limit(MAX_ITEMS_PREUVE)
                .toList();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("soldeTresorerieTotal", position.total());
        facts.put("dateReference", position.dateReference());
        facts.put("nombreLignesBancairesNonResolues", lignesNonRapprochees.size());

        List<EvidenceRefDto> evidence = lignesNonRapprochees.stream()
                .map(f -> new EvidenceRefDto(f.getSourceType(), f.getSourceId()))
                .toList();
        List<String> actions = lignesNonRapprochees.stream().map(Finding::getRecommendedAction).filter(a -> a != null).toList();

        return new FactsEtPreuves(facts, evidence, actions, lignesNonRapprochees.isEmpty() ? 0.9 : 0.75);
    }

    private FactsEtPreuves collecterFindingsParType(UUID entrepriseId, TypeFinding type) {
        List<Finding> findings = findingRepository
                .findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN)
                .stream()
                .filter(f -> f.getType() == type)
                .limit(MAX_ITEMS_PREUVE)
                .toList();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("nombre", findings.size());
        facts.put("titres", findings.stream().map(Finding::getTitle).toList());

        List<EvidenceRefDto> evidence = findings.stream()
                .map(f -> new EvidenceRefDto(f.getSourceType(), f.getSourceId()))
                .toList();
        List<String> actions = findings.stream().map(Finding::getRecommendedAction).filter(a -> a != null).toList();

        return new FactsEtPreuves(facts, evidence, actions, findings.isEmpty() ? 0.95 : 0.85);
    }

    private FactsEtPreuves collecterRetardsClients(UUID entrepriseId) {
        List<Facture> facturesEnRetard = statutPaiementFactureService.ouvertes(entrepriseId, factureRepository
                        .findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                                entrepriseId, TypeFacture.FACTURE, StatutFacture.VALIDEE, LocalDate.now()))
                .stream()
                .map(com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte::facture)
                .limit(MAX_ITEMS_PREUVE)
                .toList();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("nombreFacturesEnRetard", facturesEnRetard.size());
        facts.put("detail", facturesEnRetard.stream()
                .map(f -> f.getTiers().getNom() + " - " + f.getNumero() + " - " + f.montantTtc()
                        + " - echeance " + f.getDateEcheance())
                .toList());

        List<EvidenceRefDto> evidence = facturesEnRetard.stream()
                .map(f -> new EvidenceRefDto("FACTURE", f.getId().toString()))
                .toList();
        List<String> actions = facturesEnRetard.isEmpty() ? List.of()
                : List.of("Relancer les clients dont l'echeance est depassee.");

        return new FactsEtPreuves(facts, evidence, actions, facturesEnRetard.isEmpty() ? 0.95 : 0.9);
    }

    private FactsEtPreuves collecterEvolutionTva(UUID entrepriseId) {
        LocalDate aujourdHui = LocalDate.now();
        String periodeCourante = "%04d-%02d".formatted(aujourdHui.getYear(), aujourdHui.getMonthValue());
        LocalDate moisPrecedent = aujourdHui.minusMonths(1);
        String periodePrecedente = "%04d-%02d".formatted(moisPrecedent.getYear(), moisPrecedent.getMonthValue());

        Optional<DeclarationFiscale> courante = declarationFiscaleRepository
                .findByEntrepriseIdAndTypeImpotAndPeriode(entrepriseId, TypeImpot.TVA, periodeCourante);
        Optional<DeclarationFiscale> precedente = declarationFiscaleRepository
                .findByEntrepriseIdAndTypeImpotAndPeriode(entrepriseId, TypeImpot.TVA, periodePrecedente);

        BigDecimal montantCourant = courante.map(DeclarationFiscale::getMontant).orElse(null);
        BigDecimal montantPrecedent = precedente.map(DeclarationFiscale::getMontant).orElse(null);

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("periodeCourante", periodeCourante);
        facts.put("montantTvaPeriodeCourante", montantCourant);
        facts.put("periodePrecedente", periodePrecedente);
        facts.put("montantTvaPeriodePrecedente", montantPrecedent);
        if (montantCourant != null && montantPrecedent != null) {
            facts.put("evolution", montantCourant.subtract(montantPrecedent));
        }

        List<EvidenceRefDto> evidence = new ArrayList<>();
        courante.ifPresent(d -> evidence.add(new EvidenceRefDto("DECLARATION_FISCALE", d.getId().toString())));
        precedente.ifPresent(d -> evidence.add(new EvidenceRefDto("DECLARATION_FISCALE", d.getId().toString())));

        double confidence = (courante.isPresent() && precedente.isPresent()) ? 0.9 : 0.5;
        return new FactsEtPreuves(facts, evidence, List.of(), confidence);
    }

    private FactsEtPreuves collecterATraiterMaintenant(UUID entrepriseId, UtilisateurPrincipal principal) {
        List<Finding> findings = findingRepository
                .findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN)
                .stream()
                .limit(MAX_ITEMS_PREUVE)
                .toList();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("nombreActionsOuvertes", findings.size());
        facts.put("titres", findings.stream().map(f -> f.getSeverity() + " - " + f.getTitle()).toList());

        List<EvidenceRefDto> evidence = findings.stream()
                .map(f -> new EvidenceRefDto(f.getSourceType(), f.getSourceId()))
                .toList();
        List<String> actions = findings.stream().map(Finding::getRecommendedAction).filter(a -> a != null).toList();

        return new FactsEtPreuves(facts, evidence, actions, findings.isEmpty() ? 0.95 : 0.9);
    }

    /**
     * "Copilot Dirigeant" (mission P1.5, section 46) : meme pipeline Evidence-First, meme
     * isolation ({@link EntrepriseAccessService#verifierAcces}), meme donnees reelles - seule
     * difference, un sous-ensemble d'intentions au vocabulaire accessible sans formation
     * comptable ({@link #INTENTS_DIRIGEANT}). Jamais une deuxieme implementation du Copilot.
     */
    @Transactional(readOnly = true)
    public CopilotResponseDto repondrePourDirigeant(UUID entrepriseId, String question, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        long debut = System.currentTimeMillis();
        IntentCopilot intent = IntentClassifier.classifier(question);
        if (intent == IntentCopilot.INCONNU || !INTENTS_DIRIGEANT.contains(intent)) {
            CopilotResponseDto reponse = reponseIntentInconnuePourDirigeant();
            UUID queryId = enregistrerTelemetrie(entrepriseId, principal, "DIRIGEANT", question, "INCONNU", reponse,
                    System.currentTimeMillis() - debut);
            return avecQueryId(reponse, queryId);
        }

        FactsEtPreuves donnees = collecter(intent, entrepriseId, principal);
        String explication = copilotExplainer.expliquer(question, intent.name(), donnees.facts());

        CopilotResponseDto reponse = new CopilotResponseDto(explication, donnees.confidence(), donnees.evidence(), donnees.recommendedActions(), null);
        UUID queryId = enregistrerTelemetrie(entrepriseId, principal, "DIRIGEANT", question, intent.name(), reponse,
                System.currentTimeMillis() - debut);
        return avecQueryId(reponse, queryId);
    }

    private UUID enregistrerTelemetrie(UUID entrepriseId, UtilisateurPrincipal principal, String portee,
                                        String question, String intent, CopilotResponseDto reponse, long latenceMs) {
        return telemetryRecorder.enregistrer(principal.getTenantId(), entrepriseId, principal.getId(), portee, question,
                intent, reponse.confidence(), reponse.evidence().size(), reponse.confidence() == 0.0, latenceMs);
    }

    private static CopilotResponseDto avecQueryId(CopilotResponseDto reponse, UUID queryId) {
        return new CopilotResponseDto(reponse.answer(), reponse.confidence(), reponse.evidence(),
                reponse.recommendedActions(), queryId);
    }

    private CopilotResponseDto reponseIntentInconnuePourDirigeant() {
        return new CopilotResponseDto(
                "Je peux repondre a ce type de question : l'etat de votre dossier, ce qui bloque la cloture, "
                        + "votre solde de tresorerie, les pieces qu'il vous manque, les clients en retard de "
                        + "paiement, ou ce qu'il faut traiter maintenant.",
                0.0, List.of(), List.of(), null);
    }

    private CopilotResponseDto reponseIntentInconnue() {
        return new CopilotResponseDto(
                "Je ne peux repondre qu'a un jeu de questions structurees pour le moment : "
                        + "pourquoi le dossier n'est pas pret, ce qui bloque la cloture, l'ecart bancaire, "
                        + "les pieces manquantes, les anomalies fournisseurs, les retards clients, "
                        + "l'evolution de la TVA, ou ce qu'il faut traiter maintenant.",
                0.0, List.of(), List.of(), null);
    }

    private record FactsEtPreuves(Map<String, Object> facts, List<EvidenceRefDto> evidence,
                                   List<String> recommendedActions, double confidence) {
    }
}
