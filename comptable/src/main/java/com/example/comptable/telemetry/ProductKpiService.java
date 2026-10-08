package com.example.comptable.telemetry;

import com.example.comptable.agent.imputation.domain.ImputationSuggestion;
import com.example.comptable.agent.imputation.domain.StatutImputationSuggestion;
import com.example.comptable.agent.imputation.repository.ImputationSuggestionRepository;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.matching.domain.AiMatchProposal;
import com.example.comptable.agent.matching.domain.StatutPropositionIA;
import com.example.comptable.agent.matching.repository.AiMatchProposalRepository;
import com.example.comptable.copilot.domain.CopilotQuery;
import com.example.comptable.copilot.repository.CopilotQueryRepository;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.telemetry.web.dto.CopilotUsageDto;
import com.example.comptable.telemetry.web.dto.EvidenceResolutionDto;
import com.example.comptable.telemetry.web.dto.FindingResolutionTimeDto;
import com.example.comptable.telemetry.web.dto.HumanTouchRateDto;
import com.example.comptable.telemetry.web.dto.MatchingQualityDto;
import com.example.comptable.telemetry.web.dto.ProductKpiDto;
import com.example.comptable.telemetry.web.dto.WorkQueueDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Mission P1.9 (section 5-18) : repond aux 4 questions du chantier avec des donnees reelles,
 * jamais inventees. Reutilise integralement les entites/repositories P0/P1/P1.5 (voir
 * docs/P1_9_AUDIT.md section E) - la seule donnee vraiment nouvelle ici est {@link CopilotQuery}.
 * Aucune metrique n'est calculee si son echantillon est vide (retourne null plutot qu'un 0%
 * trompeur).
 */
@Service
public class ProductKpiService {

    private static final int FENETRE_COPILOT_JOURS = 30;

    private final EntrepriseRepository entrepriseRepository;
    private final FindingRepository findingRepository;
    private final DemandePieceRepository demandePieceRepository;
    private final AiMatchProposalRepository aiMatchProposalRepository;
    private final ImputationSuggestionRepository imputationSuggestionRepository;
    private final CopilotQueryRepository copilotQueryRepository;

    public ProductKpiService(EntrepriseRepository entrepriseRepository, FindingRepository findingRepository,
                              DemandePieceRepository demandePieceRepository,
                              AiMatchProposalRepository aiMatchProposalRepository,
                              ImputationSuggestionRepository imputationSuggestionRepository,
                              CopilotQueryRepository copilotQueryRepository) {
        this.entrepriseRepository = entrepriseRepository;
        this.findingRepository = findingRepository;
        this.demandePieceRepository = demandePieceRepository;
        this.aiMatchProposalRepository = aiMatchProposalRepository;
        this.imputationSuggestionRepository = imputationSuggestionRepository;
        this.copilotQueryRepository = copilotQueryRepository;
    }

    @Transactional(readOnly = true)
    public ProductKpiDto generer(UtilisateurPrincipal principal) {
        List<UUID> entrepriseIds = entrepriseRepository.findByTenantId(principal.getTenantId()).stream()
                .map(Entreprise::getId).toList();
        if (entrepriseIds.isEmpty()) {
            return vide();
        }

        List<AiMatchProposal> propositionsBanque = aiMatchProposalRepository.findByEntrepriseIdIn(entrepriseIds);
        List<ImputationSuggestion> propositionsCompta = imputationSuggestionRepository.findByEntrepriseIdIn(entrepriseIds);
        List<Finding> findingsResolus = findingRepository.findByEntrepriseIdInAndStatus(entrepriseIds, StatutFinding.RESOLVED);
        List<Finding> findingsOuverts = findingRepository
                .findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseIds, StatutFinding.OPEN);

        return new ProductKpiDto(
                humanTouchRate(propositionsBanque, propositionsCompta),
                matchingBancaire(propositionsBanque),
                matchingComptabilisation(propositionsCompta),
                evidenceResolution(entrepriseIds),
                findingResolutionTime(findingsResolus),
                copilotUsage(principal.getTenantId()),
                workQueue(entrepriseIds, findingsOuverts)
        );
    }

    // --- KPI n°1/2 : Human Touch Rate / Automation Rate (section 6/7) -------------------------

    private HumanTouchRateDto humanTouchRate(List<AiMatchProposal> banque, List<ImputationSuggestion> compta) {
        int automatique = 0;
        int assiste = 0;
        int humainSeul = 0;
        int enAttente = 0;

        for (AiMatchProposal p : banque) {
            switch (p.getStatus()) {
                case ACCEPTED -> { if (p.getValidatedBy() == null) automatique++; else assiste++; }
                case REJECTED -> assiste++;
                case EXPIRED -> humainSeul++;
                case PROPOSED -> enAttente++;
            }
        }
        for (ImputationSuggestion p : compta) {
            switch (p.getStatus()) {
                case APPLIED -> automatique++;
                case ACCEPTED, REJECTED -> assiste++;
                case PROPOSED -> enAttente++;
            }
        }

        int totalResolu = automatique + assiste + humainSeul;
        Double humanTouch = totalResolu == 0 ? null : ((assiste + humainSeul) * 100.0) / totalResolu;
        Double automation = totalResolu == 0 ? null : (automatique * 100.0) / totalResolu;
        return new HumanTouchRateDto(automatique, assiste, humainSeul, enAttente, totalResolu, humanTouch, automation);
    }

    // --- KPI n°3 : Matching Quality (section 8), un DTO par domaine ---------------------------

    private MatchingQualityDto matchingBancaire(List<AiMatchProposal> propositions) {
        int acceptees = compter(propositions, p -> p.getStatus() == StatutPropositionIA.ACCEPTED);
        int rejetees = compter(propositions, p -> p.getStatus() == StatutPropositionIA.REJECTED);
        int enAttente = compter(propositions, p -> p.getStatus() == StatutPropositionIA.PROPOSED);
        int expirees = compter(propositions, p -> p.getStatus() == StatutPropositionIA.EXPIRED);
        int total = propositions.size();
        int tranchees = acceptees + rejetees;
        return new MatchingQualityDto("BANCAIRE", total, acceptees, rejetees, enAttente, expirees,
                tranchees == 0 ? null : (acceptees * 100.0) / tranchees,
                tranchees == 0 ? null : (rejetees * 100.0) / tranchees);
    }

    private MatchingQualityDto matchingComptabilisation(List<ImputationSuggestion> propositions) {
        int acceptees = compter(propositions, p -> p.getStatus() == StatutImputationSuggestion.ACCEPTED
                || p.getStatus() == StatutImputationSuggestion.APPLIED);
        int rejetees = compter(propositions, p -> p.getStatus() == StatutImputationSuggestion.REJECTED);
        int enAttente = compter(propositions, p -> p.getStatus() == StatutImputationSuggestion.PROPOSED);
        int total = propositions.size();
        int tranchees = acceptees + rejetees;
        return new MatchingQualityDto("COMPTABILISATION", total, acceptees, rejetees, enAttente, 0,
                tranchees == 0 ? null : (acceptees * 100.0) / tranchees,
                tranchees == 0 ? null : (rejetees * 100.0) / tranchees);
    }

    private <T> int compter(List<T> liste, java.util.function.Predicate<T> filtre) {
        return (int) liste.stream().filter(filtre).count();
    }

    // --- KPI n°5 : Evidence Resolution (section 10) --------------------------------------------

    private EvidenceResolutionDto evidenceResolution(List<UUID> entrepriseIds) {
        long completees = demandePieceRepository.countByEntrepriseIdInAndStatut(entrepriseIds, StatutDemandePiece.RECU)
                + demandePieceRepository.countByEntrepriseIdInAndStatut(entrepriseIds, StatutDemandePiece.RAPPROCHEE);
        long enAttente = demandePieceRepository.countByEntrepriseIdInAndStatut(entrepriseIds, StatutDemandePiece.EN_ATTENTE);
        long annulees = demandePieceRepository.countByEntrepriseIdInAndStatut(entrepriseIds, StatutDemandePiece.ANNULEE);
        long total = completees + enAttente + annulees;

        List<DemandePiece> resolues = new java.util.ArrayList<>(demandePieceRepository.findByEntrepriseIdInAndStatutOrderByDateLimiteAsc(entrepriseIds, StatutDemandePiece.RECU));
        resolues.addAll(demandePieceRepository.findByEntrepriseIdInAndStatutOrderByDateLimiteAsc(entrepriseIds, StatutDemandePiece.RAPPROCHEE));
        Double dureeMoyenneHeures = resolues.stream()
                .filter(d -> d.getResolueAt() != null)
                .mapToDouble(d -> Duration.between(d.getCreatedAt(), d.getResolueAt()).toMinutes() / 60.0)
                .average().stream().boxed().findFirst().orElse(null);

        return new EvidenceResolutionDto((int) total, (int) completees, (int) annulees, (int) enAttente,
                total == 0 ? null : (completees * 100.0) / total, dureeMoyenneHeures);
    }

    // --- KPI n°7 : Finding Resolution Time (section 12) -----------------------------------------

    private FindingResolutionTimeDto findingResolutionTime(List<Finding> findingsResolus) {
        List<Double> dureesHeures = findingsResolus.stream()
                .filter(f -> f.getResolvedAt() != null)
                .map(f -> Duration.between(f.getCreatedAt(), f.getResolvedAt()).toMinutes() / 60.0)
                .sorted()
                .toList();

        if (dureesHeures.isEmpty()) {
            return new FindingResolutionTimeDto(0, null, null, null);
        }

        double moyenne = dureesHeures.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double mediane = percentile(dureesHeures, 0.50);
        double p90 = percentile(dureesHeures, 0.90);
        return new FindingResolutionTimeDto(dureesHeures.size(), moyenne, mediane, p90);
    }

    /** Interpolation lineaire simple - suffisant pour un tableau de bord interne, pas une
     * statistique publiee. */
    private double percentile(List<Double> triees, double p) {
        if (triees.size() == 1) {
            return triees.get(0);
        }
        double indice = p * (triees.size() - 1);
        int bas = (int) Math.floor(indice);
        int haut = (int) Math.ceil(indice);
        if (bas == haut) {
            return triees.get(bas);
        }
        double poids = indice - bas;
        return triees.get(bas) * (1 - poids) + triees.get(haut) * poids;
    }

    // --- KPI n°8/9 : Copilot Usage/Quality (section 13/14) --------------------------------------

    private CopilotUsageDto copilotUsage(UUID tenantId) {
        OffsetDateTime depuis = OffsetDateTime.now().minusDays(FENETRE_COPILOT_JOURS);
        List<CopilotQuery> requetes = copilotQueryRepository.findByTenantIdAndCreatedAtAfter(tenantId, depuis);

        int total = requetes.size();
        int avecPreuve = compter(requetes, q -> q.getEvidenceCount() > 0);
        int fallback = compter(requetes, CopilotQuery::isFallback);
        int positif = compter(requetes, q -> "POSITIVE".equals(q.getFeedback()));
        int negatif = compter(requetes, q -> "NEGATIVE".equals(q.getFeedback()));
        int sansFeedback = total - positif - negatif;
        int avecFeedback = positif + negatif;

        Double latenceMoyenne = requetes.stream()
                .map(CopilotQuery::getLatencyMs).filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue).average().stream().boxed().findFirst().orElse(null);

        return new CopilotUsageDto(total, avecPreuve, fallback, latenceMoyenne, positif, negatif, sansFeedback,
                total == 0 ? null : (fallback * 100.0) / total,
                avecFeedback == 0 ? null : (positif * 100.0) / avecFeedback,
                avecFeedback == 0 ? null : (negatif * 100.0) / avecFeedback,
                total == 0 ? null : (avecPreuve * 100.0) / total);
    }

    // --- KPI n°12 : Work Queue (section 17) ------------------------------------------------------

    private WorkQueueDto workQueue(List<UUID> entrepriseIds, List<Finding> findingsOuverts) {
        LocalDate aujourdHui = LocalDate.now();
        int findingsEnRetard = compter(findingsOuverts, f -> f.getDueDate() != null && f.getDueDate().isBefore(aujourdHui));
        long demandesEnAttente = demandePieceRepository.countByEntrepriseIdInAndStatut(entrepriseIds, StatutDemandePiece.EN_ATTENTE);
        long demandesEnRetard = demandePieceRepository.countByEntrepriseIdInAndStatutAndDateLimiteBefore(
                entrepriseIds, StatutDemandePiece.EN_ATTENTE, aujourdHui);

        return new WorkQueueDto(findingsOuverts.size(), findingsEnRetard, (int) demandesEnAttente, (int) demandesEnRetard);
    }

    private ProductKpiDto vide() {
        MatchingQualityDto vide1 = new MatchingQualityDto("BANCAIRE", 0, 0, 0, 0, 0, null, null);
        MatchingQualityDto vide2 = new MatchingQualityDto("COMPTABILISATION", 0, 0, 0, 0, 0, null, null);
        return new ProductKpiDto(
                new HumanTouchRateDto(0, 0, 0, 0, 0, null, null),
                vide1, vide2,
                new EvidenceResolutionDto(0, 0, 0, 0, null, null),
                new FindingResolutionTimeDto(0, null, null, null),
                new CopilotUsageDto(0, 0, 0, null, 0, 0, 0, null, null, null, null),
                new WorkQueueDto(0, 0, 0, 0));
    }
}
