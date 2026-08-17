package com.example.comptable.agent.relance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Declenche {@link RelancePieceService#executerRelancesDuJour()} chaque matin. Choix de
 * {@code @Scheduled} plutot que Spring Batch : {@code spring-boot-starter-batch} est bien dans le
 * pom, mais aucun Job/Step n'est configure nulle part dans ce projet (verifie : zero import
 * {@code org.springframework.batch} dans le code) - la dependance est presente mais inutilisee,
 * "un Job Spring Batch simple deja present dans la stack" ne correspond donc a rien de reel a
 * brancher aujourd'hui. Pour une boucle quotidienne sur un volume modeste (les entreprises d'un
 * cabinet), Spring Batch (JobRepository, schema dedie, semantique chunk/restart) serait
 * disproportionne face a {@code @Scheduled} + une transaction simple ; a reconsiderer seulement
 * si le volume de relances justifie un jour un vrai traitement par lots avec reprise sur erreur.
 * <p>
 * Cron par defaut : tous les jours a 8h (pas seulement "fin de mois") - une piece en retard le 5
 * du mois n'a aucune raison d'attendre le 28 pour declencher une relance, la date limite de
 * chaque {@code DemandePiece} est deja le vrai signal de retard (cf. javadoc {@link RelancePieceService}).
 */
@Component
public class RelanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(RelanceScheduler.class);

    private final RelancePieceService relancePieceService;
    private final boolean active;

    public RelanceScheduler(RelancePieceService relancePieceService,
                             @Value("${relance.enabled:false}") boolean active) {
        this.relancePieceService = relancePieceService;
        this.active = active;
    }

    @Scheduled(cron = "${relance.cron:0 0 8 * * *}")
    public void executerRelanceQuotidienne() {
        if (!active) {
            log.debug("Relance WhatsApp desactivee (relance.enabled=false), rien a faire");
            return;
        }
        log.info("Debut de la relance WhatsApp quotidienne des pieces en retard");
        RelanceResumeDto resume = relancePieceService.executerRelancesDuJour();
        log.info("Relance WhatsApp terminee : {} envoyee(s), {} echec(s), {} ignoree(s)",
                resume.envoyees(), resume.echecs(), resume.ignorees());
    }
}
