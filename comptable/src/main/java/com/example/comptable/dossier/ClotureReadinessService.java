package com.example.comptable.dossier;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "Closing Readiness" (mission P0, section 19) : agrege les blocages provenant de l'exercice
 * courant (ecritures BROUILLON, meme regle deja appliquee par
 * {@code ClotureExerciceService.cloturer}) et des {@link Finding} ouverts, sans jamais declencher
 * une cloture reelle - purement consultatif, prepare et explique, ne clot jamais automatiquement.
 */
@Service
public class ClotureReadinessService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final FindingRepository findingRepository;

    public ClotureReadinessService(EntrepriseAccessService entrepriseAccessService,
                                    ExerciceComptableRepository exerciceComptableRepository,
                                    EcritureComptableRepository ecritureComptableRepository,
                                    FindingRepository findingRepository) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.exerciceComptableRepository = exerciceComptableRepository;
        this.ecritureComptableRepository = ecritureComptableRepository;
        this.findingRepository = findingRepository;
    }

    @Transactional(readOnly = true)
    public ClotureReadinessDto evaluer(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        List<String> blockingItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        Optional<ExerciceComptable> exercice = exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.now());
        if (exercice.isEmpty()) {
            blockingItems.add("Aucun exercice comptable ne couvre la date du jour.");
        } else if (exercice.get().getStatut() != StatutExercice.OUVERT) {
            warnings.add("L'exercice courant est deja cloture.");
        } else {
            long brouillons = ecritureComptableRepository.countByExercice_IdAndStatut(
                    exercice.get().getId(), StatutEcriture.BROUILLON);
            if (brouillons > 0) {
                blockingItems.add(brouillons + " ecriture(s) en brouillon doivent etre validees avant cloture.");
                recommendations.add("Valider ou supprimer les ecritures brouillon restantes.");
            }
        }

        List<Finding> findingsOuverts = findingRepository
                .findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN);
        for (Finding finding : findingsOuverts) {
            if (finding.getSeverity() == SeveriteFinding.CRITICAL) {
                blockingItems.add(finding.getTitle());
            } else if (finding.getSeverity() == SeveriteFinding.IMPORTANT) {
                warnings.add(finding.getTitle());
            }
        }

        if (!warnings.isEmpty() && recommendations.isEmpty()) {
            recommendations.add("Examiner les elements a surveiller depuis l'Inbox avant de cloturer.");
        }

        String statut;
        if (!blockingItems.isEmpty()) {
            statut = "NOT_READY";
        } else if (!warnings.isEmpty()) {
            statut = "READY_WITH_REVIEW";
        } else {
            statut = "READY";
        }

        return new ClotureReadinessDto(statut, blockingItems, warnings, recommendations);
    }
}
