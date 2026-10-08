package com.example.comptable.controle;

import com.example.comptable.comptabilite.service.RapprochementAutomatiqueService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Execute les {@link ControlDetector} d'une entreprise et reconcilie les Findings. Une lecture
 * (dossier, revue) n'impose pas un recalcul complet : {@link #executerSiPerime} reutilise la
 * derniere execution tant qu'elle est recente ; {@link #executer} force le passage.
 *
 * <p>Un detecteur en echec n'ecrit rien et ne ferme rien (jamais de resolution automatique sur
 * une detection incomplete) ; les autres detecteurs continuent.</p>
 */
@Service
public class ControlRunService {

    private static final Logger log = LoggerFactory.getLogger(ControlRunService.class);

    private final List<ControlDetector> detecteurs;
    private final ControlReconciler reconciler;
    private final ControlRunRepository runRepository;
    private final RapprochementAutomatiqueService rapprochementAutomatiqueService;
    private final EntrepriseRepository entrepriseRepository;
    private final com.example.comptable.comptabilite.allocation.AllocationAutomatiqueService allocationAutomatiqueService;
    private final com.example.comptable.banque.InternalTransferService internalTransferService;
    private final long ttlSecondes;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public ControlRunService(List<ControlDetector> detecteurs, ControlReconciler reconciler,
                             ControlRunRepository runRepository, RapprochementAutomatiqueService rapprochementAutomatiqueService, EntrepriseRepository entrepriseRepository,
                             com.example.comptable.comptabilite.allocation.AllocationAutomatiqueService allocationAutomatiqueService,
                             com.example.comptable.banque.InternalTransferService internalTransferService,
                             @Value("${controles.ttl-secondes:15}") long ttlSecondes) {
        this.detecteurs = detecteurs;
        this.reconciler = reconciler;
        this.runRepository = runRepository;
        this.rapprochementAutomatiqueService = rapprochementAutomatiqueService;
        this.entrepriseRepository = entrepriseRepository;
        this.allocationAutomatiqueService = allocationAutomatiqueService;
        this.internalTransferService = internalTransferService;
        this.ttlSecondes = ttlSecondes;
    }

    /**
     * Serialise les executions d'UNE entreprise (verrou consultatif PostgreSQL, libere en fin de transaction) : deux ecrans qui ouvrent en meme temps (revue +
     * pre-cloture) ne declarent jamais le meme constat deux fois (violation d'unicite -> 500, constate au test navigateur de la phase E). Les autres entreprises
     * ne sont pas ralenties.
     */
    private void verrouillerExecution(UUID entrepriseId) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(cast(:cle as text), 0))").setParameter("cle", "controles:" + entrepriseId).getSingleResult();
    }

    @Transactional
    public ControlReconciler.Bilan executer(Entreprise entreprise) {
        verrouillerExecution(entreprise.getId());
        long debut = System.currentTimeMillis();
        OffsetDateTime demarrage = OffsetDateTime.now();
        ControlReconciler.Bilan total = ControlReconciler.Bilan.vide();
        Map<String, Object> detail = new LinkedHashMap<>();
        // Un virement interne (banque A -> banque B) n'est ni une charge ni un reglement de tiers : ses deux jambes sont liees AVANT tout rapprochement.
        try {
            var virements = internalTransferService.detecter(entreprise);
            detail.put("virementsInternes", Map.of("confirmes", virements.confirmes(), "proposes", virements.proposes(), "ambigus", virements.ambigus()));
        } catch (RuntimeException e) {
            log.warn("Detection des virements internes en echec pour l'entreprise {} : {}", entreprise.getId(), e.toString());
            detail.put("virementsInternes", "erreur : " + e.getMessage());
        }
        // CERTAIN -> automatise : les rapprochements bancaires EXACTS sont pointes avant la detection (audite).
        try {
            detail.put("rapprochementsExactsPointes", rapprochementAutomatiqueService.appliquerExacts(entreprise));
        } catch (RuntimeException e) {
            log.warn("Rapprochement automatique en echec pour l'entreprise {} : {}", entreprise.getId(), e.toString());
            detail.put("rapprochementsExactsPointes", "erreur : " + e.getMessage());
        }
        // CERTAIN -> automatise : un reglement et une facture ouverts du meme tiers et du meme reste, sans concurrent, s'allouent seuls.
        try {
            detail.put("allocationsAutomatiques", allocationAutomatiqueService.allouerExacts(entreprise));
        } catch (RuntimeException e) {
            log.warn("Allocation automatique en echec pour l'entreprise {} : {}", entreprise.getId(), e.toString());
            detail.put("allocationsAutomatiques", "erreur : " + e.getMessage());
        }
        for (ControlDetector detecteur : detecteurs) {
            long debutDetecteur = System.currentTimeMillis();
            try {
                ControlReconciler.Bilan bilan = reconciler.reconcilier(entreprise, detecteur.codes(), detecteur.detecter(entreprise));
                total = total.plus(bilan);
                detail.put(detecteur.nom(), Map.of("ms", System.currentTimeMillis() - debutDetecteur,
                        "detectes", bilan.detectes(), "crees", bilan.crees(), "resolus", bilan.resolus()));
            } catch (RuntimeException e) {
                log.warn("Detecteur {} en echec pour l'entreprise {} : {}", detecteur.nom(), entreprise.getId(), e.toString());
                detail.put(detecteur.nom(), Map.of("erreur", String.valueOf(e.getMessage())));
            }
        }
        ControlRun run = new ControlRun();
        run.setEntrepriseId(entreprise.getId());
        run.setStartedAt(demarrage);
        run.setDurationMs(System.currentTimeMillis() - debut);
        run.setDetectedCount(total.detectes());
        run.setCreatedCount(total.crees());
        run.setUpdatedCount(total.misAJour());
        run.setResolvedCount(total.resolus());
        run.setDetail(detail);
        runRepository.save(run);
        return total;
    }

    /** Forme interne (cloture, jobs) : l'appelant a deja verifie l'acces a l'entreprise. */
    @Transactional
    public ControlReconciler.Bilan executer(UUID entrepriseId) {
        return executer(entrepriseRepository.findById(entrepriseId)
                .orElseThrow(() -> new IllegalArgumentException("Entreprise introuvable : " + entrepriseId)));
    }

    /** @return {@code true} si les detecteurs ont ete executes, {@code false} si la derniere execution est encore recente. */
    /**
     * Transaction INDEPENDANTE : les lectures (dossier, cockpit, copilote) s'executent souvent dans une
     * transaction en lecture seule, qui ne peut pas ecrire les Findings ni le journal d'execution.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean executerSiPerime(Entreprise entreprise) {
        // Le verrou est pris AVANT de juger de la fraicheur : si une autre execution vient de se terminer, on la reutilise au lieu de la refaire.
        verrouillerExecution(entreprise.getId());
        boolean recent = runRepository.findFirstByEntrepriseIdOrderByStartedAtDesc(entreprise.getId())
                .map(r -> r.getStartedAt().isAfter(OffsetDateTime.now().minusSeconds(ttlSecondes)))
                .orElse(false);
        if (recent) {
            return false;
        }
        executer(entreprise);
        return true;
    }

    /** Horodatage de la derniere execution des controles : une pre-cloture le publie pour que l'on sache sur quoi elle s'appuie. */
    @Transactional(readOnly = true)
    public java.util.Optional<OffsetDateTime> derniereExecution(UUID entrepriseId) {
        return runRepository.findFirstByEntrepriseIdOrderByStartedAtDesc(entrepriseId).map(r -> r.getStartedAt());
    }
}
