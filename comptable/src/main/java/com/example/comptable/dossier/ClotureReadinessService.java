package com.example.comptable.dossier;

import com.example.comptable.audit.AuditTrailService;
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
import com.example.comptable.dossier.controles.ClotureControle;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.dossier.web.dto.ControlItem;
import com.example.comptable.controle.ControlDomain;
import com.example.comptable.controle.ControlRunService;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p>Phase 1 (lots 1.10/1.11) : des controles deterministes ({@link ClotureControle}, package
 * {@code dossier.controles}) remplacent la plupart des anciens points {@code NOT_APPLICABLE}
 * (banque, lettrage, fournisseurs, clients, stocks, amortissements, depreciations, provisions,
 * fiscalite) et ajoutent equilibre des ecritures, comptes desactives, parametrage, factures non
 * comptabilisees et pieces manquantes. Seuls {@code caisse}, {@code rapprochement} et
 * {@code immobilisations} restent {@code NOT_APPLICABLE} faute de donnees permettant un controle
 * certain, et un point sans objet a controler (ex. aucun releve importe) le reste aussi : jamais
 * simules comme OK.</p>
 */
@Service
public class ClotureReadinessService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final FindingRepository findingRepository;
    private final RegularisationRepository regularisationRepository;
    private final List<ClotureControle> controles;
    private final AuditTrailService auditTrailService;
    private final ControlRunService controlRunService;

    @Autowired
    public ClotureReadinessService(EntrepriseAccessService entrepriseAccessService,
                                    ExerciceComptableRepository exerciceComptableRepository,
                                    EcritureComptableRepository ecritureComptableRepository,
                                    FindingRepository findingRepository,
                                    RegularisationRepository regularisationRepository,
                                    List<ClotureControle> controles, AuditTrailService auditTrailService,
                                    ControlRunService controlRunService) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.exerciceComptableRepository = exerciceComptableRepository;
        this.ecritureComptableRepository = ecritureComptableRepository;
        this.findingRepository = findingRepository;
        this.regularisationRepository = regularisationRepository;
        this.controles = controles != null ? controles : List.of();
        this.auditTrailService = auditTrailService;
        this.controlRunService = controlRunService;
    }

    /** Sans controles deterministes supplementaires : la checklist historique (5 points reels + points NOT_APPLICABLE). */
    public ClotureReadinessService(EntrepriseAccessService entrepriseAccessService,
                                    ExerciceComptableRepository exerciceComptableRepository,
                                    EcritureComptableRepository ecritureComptableRepository,
                                    FindingRepository findingRepository,
                                    RegularisationRepository regularisationRepository) {
        this(entrepriseAccessService, exerciceComptableRepository, ecritureComptableRepository, findingRepository,
                regularisationRepository, List.of(), null, null);
    }

    /** Pas {@code readOnly} : la consultation par un utilisateur ecrit un evenement d'audit. */
    @Transactional
    public ClotureReadinessDto evaluer(UUID entrepriseId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (controlRunService != null) {
            controlRunService.executerSiPerime(entreprise);
        }
        ClotureReadinessDto checklist = construireChecklist(entrepriseId);
        // Une consultation de la pre-cloture par un utilisateur est une action tracee (pas les appels internes).
        if (auditTrailService != null) {
            auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "CLOTURE_CONTROLEE",
                    "ENTREPRISE", entrepriseId.toString(), java.util.Map.of("statut", checklist.statut(),
                            "bloquants", checklist.blockingItems().size(), "avertissements", checklist.warnings().size()));
        }
        return checklist;
    }

    /** Remet les Findings a jour (detecteurs + rapprochements certains) avant qu'une cloture ne s'appuie sur eux. */
    @Transactional
    public void actualiserControles(UUID entrepriseId) {
        if (controlRunService != null) {
            controlRunService.executer(entrepriseId);
        }
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
            // ClotureExerciceService au moment de la cloture, via AccountingAccountResolver - jamais
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
        List<String> idsCritiques = new ArrayList<>();
        List<String> idsImportants = new ArrayList<>();
        Map<String, Integer> parCategorie = new java.util.TreeMap<>();
        for (Finding finding : findingsOuverts) {
            if (finding.getControlSeverity() == ControlSeverity.BLOCKING) {
                blockingItems.add(finding.getTitle());
                titresCritiques.add(finding.getTitle());
                idsCritiques.add(finding.getId().toString());
                parCategorie.merge(finding.getCategory().name(), 1, Integer::sum);
            } else if (finding.getControlSeverity() == ControlSeverity.ERROR || finding.getControlSeverity() == ControlSeverity.WARNING) {
                warnings.add(finding.getTitle());
                titresImportants.add(finding.getTitle());
                idsImportants.add(finding.getId().toString());
                parCategorie.merge(finding.getCategory().name(), 1, Integer::sum);
            }
        }
        Map<String, Object> metaFindings = new java.util.LinkedHashMap<>();
        metaFindings.put("parCategorie", parCategorie);
        if (!titresCritiques.isEmpty()) {
            String message = titresCritiques.size() + " finding(s) critique(s) empechent la cloture.";
            checklist.add(ControlItem.detaille("findings", "Alertes (Inbox)", "BLOCKING", "CRITICAL", message,
                    String.join("; ", titresCritiques), "FINDING", idsCritiques.get(0),
                    message + " Ouvrir la revue pour les traiter : " + String.join("; ", titresCritiques) + ".",
                    idsCritiques.size() > 100 ? idsCritiques.subList(0, 100) : idsCritiques, metaFindings));
        } else if (!titresImportants.isEmpty()) {
            String message = titresImportants.size() + " finding(s) important(s) a examiner.";
            checklist.add(ControlItem.detaille("findings", "Alertes (Inbox)", "WARNING", "IMPORTANT", message,
                    String.join("; ", titresImportants), "FINDING", idsImportants.get(0),
                    message + " " + String.join("; ", titresImportants) + ".",
                    idsImportants.size() > 100 ? idsImportants.subList(0, 100) : idsImportants, metaFindings));
        } else {
            checklist.add(new ControlItem("findings", "Alertes (Inbox)", "OK", null, "Aucun finding ouvert critique ou important.", null));
        }

        // Controles deterministes (Phase 1, lots 1.10/1.11) : uniquement sur un exercice ouvert.
        if (exerciceCourant.isPresent() && exerciceCourant.get().getStatut() == StatutExercice.OUVERT) {
            ClotureControle.Contexte contexte = new ClotureControle.Contexte(entrepriseId, exerciceCourant.get());
            for (ClotureControle controle : controles) {
                for (ControlItem item : controle.evaluer(contexte)) {
                    checklist.add(item);
                    if ("BLOCKING".equals(item.statut())) {
                        blockingItems.add(item.message());
                    } else if ("WARNING".equals(item.statut())) {
                        warnings.add(item.message());
                    }
                }
            }
        }

        // Points sans controle certain : restent NOT_APPLICABLE (jamais simules comme OK).
        for (String[] item : NON_APPLICABLES) {
            boolean dejaEvalue = checklist.stream().anyMatch(existant -> existant.code().equals(item[0]));
            if (!dejaEvalue) {
                checklist.add(new ControlItem(item[0], item[1], "NOT_APPLICABLE", null,
                        "Aucune logique de controle approfondie construite pour ce point dans ce perimetre.", null));
            }
        }

        if (!warnings.isEmpty() && recommendations.isEmpty()) {
            recommendations.add("Examiner les elements a surveiller depuis l'Inbox avant de cloturer.");
        }

        String statut;
        if (!blockingItems.isEmpty()) {
            statut = "NOT_READY";
        } else if (!warnings.isEmpty()) {
            statut = "READY_WITH_WARNINGS";
        } else {
            statut = "READY";
        }

        // Chaque point est range dans un domaine de revision (meme table que les constats) et le resume COMPTE : aucun pourcentage.
        List<ControlItem> classes = checklist.stream().map(i -> i.domaine() != null ? i
                : i.avecDomaine(ControlDomain.de(i.code(), null).name())).toList();
        long bloquants = classes.stream().filter(i -> "BLOCKING".equals(i.statut())).count();
        long avertissements = classes.stream().filter(i -> "WARNING".equals(i.statut())).count();
        long enOrdre = classes.stream().filter(i -> "OK".equals(i.statut())).count();
        long nonApplicables = classes.stream().filter(i -> "NOT_APPLICABLE".equals(i.statut())).count();
        java.time.OffsetDateTime derniereExecution = controlRunService != null ? controlRunService.derniereExecution(entrepriseId).orElse(null) : null;
        return new ClotureReadinessDto(statut, blockingItems, warnings, recommendations, classes,
                new ClotureReadinessDto.Resume(bloquants, avertissements, enOrdre, nonApplicables), java.time.OffsetDateTime.now(), derniereExecution);
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
