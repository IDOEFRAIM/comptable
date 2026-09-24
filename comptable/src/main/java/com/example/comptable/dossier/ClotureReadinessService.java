package com.example.comptable.dossier;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.regularisation.domain.Regularisation;
import com.example.comptable.comptabilite.regularisation.domain.StatutRegularisation;
import com.example.comptable.comptabilite.regularisation.repository.RegularisationRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.dossier.web.dto.ControlItem;
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
 * "Closing Readiness" (mission P0 puis structuree en checklist par la mission cloture Etape 4,
 * section 14) : agrege les blocages provenant de l'exercice courant, des {@link Finding} ouverts
 * et des regularisations non finalisees, sans jamais declencher une cloture reelle - purement
 * consultatif. {@code ClotureExerciceService} consulte cette meme checklist (voir
 * {@link #construireChecklist}) et refuse la cloture si un item est BLOCKING (section 15 : la
 * cloture n'est jamais automatique).
 *
 * <p>Plusieurs items de la checklist ({@code banque}, {@code caisse}, {@code rapprochement},
 * {@code lettrage}, {@code fournisseurs}, {@code clients}, {@code stocks}, {@code immobilisations},
 * {@code amortissements}, {@code fiscalite}) restent volontairement {@code NOT_APPLICABLE} :
 * aucune logique metier suffisamment approfondie n'a ete construite pour ces points dans cette
 * mission - jamais simules comme OK.</p>
 */
@Service
public class ClotureReadinessService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final FindingRepository findingRepository;
    private final RegularisationRepository regularisationRepository;

    public ClotureReadinessService(EntrepriseAccessService entrepriseAccessService,
                                    ExerciceComptableRepository exerciceComptableRepository,
                                    EcritureComptableRepository ecritureComptableRepository,
                                    FindingRepository findingRepository,
                                    RegularisationRepository regularisationRepository) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.exerciceComptableRepository = exerciceComptableRepository;
        this.ecritureComptableRepository = ecritureComptableRepository;
        this.findingRepository = findingRepository;
        this.regularisationRepository = regularisationRepository;
    }

    @Transactional(readOnly = true)
    public ClotureReadinessDto evaluer(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return construireChecklist(entrepriseId);
    }

    /** Vue consultative : la checklist porte sur l'exercice qui couvre la date du jour. */
    @Transactional(readOnly = true)
    public ClotureReadinessDto construireChecklist(UUID entrepriseId) {
        return construireChecklist(entrepriseId,
                exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.now()).orElse(null));
    }

    /** Reutilise par {@code ClotureExerciceService} avant de generer l'ecriture de cloture (section
     * 15) - la meme checklist sert a la fois a l'affichage consultatif et au controle bloquant.
     * {@code exerciceCible} est l'exercice reellement inspecte (ecritures brouillon, regularisations
     * non finalisees) : la cloture d'un exercice anterieur ({@code exerciceId} != exercice courant)
     * doit verifier CET exercice-la, jamais celui qui couvre la date du jour. */
    @Transactional(readOnly = true)
    public ClotureReadinessDto construireChecklist(UUID entrepriseId, ExerciceComptable exerciceCible) {
        List<String> blockingItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        List<ControlItem> checklist = new ArrayList<>();

        Optional<ExerciceComptable> exerciceCourant = Optional.ofNullable(exerciceCible);

        if (exerciceCourant.isEmpty()) {
            blockingItems.add("Aucun exercice comptable ne couvre la date du jour.");
            checklist.add(new ControlItem("exercices", "Exercice comptable", "BLOCKING", "CRITICAL",
                    "Aucun exercice comptable ne couvre la date du jour.", null));
        } else if (exerciceCourant.get().getStatut() != StatutExercice.OUVERT) {
            warnings.add("L'exercice courant est deja cloture.");
            checklist.add(new ControlItem("exercices", "Exercice comptable", "WARNING", "IMPORTANT",
                    "L'exercice courant est deja cloture.", null));
        } else {
            checklist.add(new ControlItem("exercices", "Exercice comptable", "OK", null,
                    "Un exercice ouvert couvre la date du jour.", null));
        }

        if (exerciceCourant.isPresent() && exerciceCourant.get().getStatut() == StatutExercice.OUVERT) {
            ExerciceComptable exercice = exerciceCourant.get();

            long brouillons = ecritureComptableRepository.countByExercice_IdAndStatut(
                    exercice.getId(), StatutEcriture.BROUILLON);
            if (brouillons > 0) {
                String message = brouillons + " ecriture(s) en brouillon doivent etre validees avant cloture.";
                blockingItems.add(message);
                recommendations.add("Valider ou supprimer les ecritures brouillon restantes.");
                checklist.add(new ControlItem("brouillons", "Ecritures brouillon", "BLOCKING", "CRITICAL",
                        message, String.valueOf(brouillons)));
            } else {
                checklist.add(new ControlItem("brouillons", "Ecritures brouillon", "OK", null,
                        "Aucune ecriture en brouillon.", null));
            }

            List<Regularisation> nonFinalisees = regularisationRepository
                    .findByEntrepriseIdAndExerciceId(entrepriseId, exercice.getId()).stream()
                    .filter(r -> r.getStatut() == StatutRegularisation.BROUILLON || r.getStatut() == StatutRegularisation.PROPOSEE)
                    .toList();
            if (!nonFinalisees.isEmpty()) {
                String message = nonFinalisees.size() + " regularisation(s) non finalisee(s) (brouillon ou en attente de validation).";
                warnings.add(message);
                checklist.add(new ControlItem("regularisations", "Regularisations de fin de periode", "WARNING", "IMPORTANT",
                        message, String.valueOf(nonFinalisees.size())));
            } else {
                checklist.add(new ControlItem("regularisations", "Regularisations de fin de periode", "OK", null,
                        "Aucune regularisation en attente pour cet exercice.", null));
            }

            // Determination du resultat : le calcul reel (classes 6/7 -> resultat) est effectue par
            // ClotureExerciceService au moment de la cloture, via CompteSystemeResolver - jamais
            // recalcule ici en double. Ce point informe seulement que le calcul est possible.
            checklist.add(new ControlItem("resultat", "Determination du resultat", "OK", null,
                    "Sera calcule automatiquement a la cloture (classes 6/7).", null));
        } else {
            checklist.add(new ControlItem("brouillons", "Ecritures brouillon", "NOT_APPLICABLE", null,
                    "Aucun exercice ouvert a verifier.", null));
            checklist.add(new ControlItem("regularisations", "Regularisations de fin de periode", "NOT_APPLICABLE", null,
                    "Aucun exercice ouvert a verifier.", null));
            checklist.add(new ControlItem("resultat", "Determination du resultat", "NOT_APPLICABLE", null,
                    "Aucun exercice ouvert a verifier.", null));
        }

        List<Finding> findingsOuverts = findingRepository
                .findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN);
        List<String> titresCritiques = new ArrayList<>();
        List<String> titresImportants = new ArrayList<>();
        for (Finding finding : findingsOuverts) {
            if (finding.getSeverity() == SeveriteFinding.CRITICAL) {
                blockingItems.add(finding.getTitle());
                titresCritiques.add(finding.getTitle());
            } else if (finding.getSeverity() == SeveriteFinding.IMPORTANT) {
                warnings.add(finding.getTitle());
                titresImportants.add(finding.getTitle());
            }
        }
        if (!titresCritiques.isEmpty()) {
            checklist.add(new ControlItem("findings", "Alertes (Inbox)", "BLOCKING", "CRITICAL",
                    titresCritiques.size() + " finding(s) critique(s) empechent la cloture.", String.join("; ", titresCritiques)));
        } else if (!titresImportants.isEmpty()) {
            checklist.add(new ControlItem("findings", "Alertes (Inbox)", "WARNING", "IMPORTANT",
                    titresImportants.size() + " finding(s) important(s) a examiner.", String.join("; ", titresImportants)));
        } else {
            checklist.add(new ControlItem("findings", "Alertes (Inbox)", "OK", null, "Aucun finding ouvert critique ou important.", null));
        }

        for (String[] item : NON_APPLICABLES) {
            checklist.add(new ControlItem(item[0], item[1], "NOT_APPLICABLE", null,
                    "Aucune logique de controle approfondie construite pour ce point dans ce perimetre.", null));
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

        return new ClotureReadinessDto(statut, blockingItems, warnings, recommendations, checklist);
    }

    private static final String[][] NON_APPLICABLES = {
            {"banque", "Rapprochement bancaire"},
            {"caisse", "Caisse"},
            {"rapprochement", "Rapprochement general"},
            {"lettrage", "Lettrage des comptes de tiers"},
            {"fournisseurs", "Comptes fournisseurs"},
            {"clients", "Comptes clients"},
            {"stocks", "Valorisation des stocks"},
            {"immobilisations", "Immobilisations"},
            {"amortissements", "Amortissements"},
            {"depreciations", "Depreciations"},
            {"provisions", "Provisions"},
            {"fiscalite", "Fiscalite"},
    };
}
