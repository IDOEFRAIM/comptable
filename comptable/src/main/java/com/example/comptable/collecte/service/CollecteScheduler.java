package com.example.comptable.collecte.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Deux boucles independantes, desactivees par defaut (aucun fournisseur de messagerie n'est suppose configure) :
 * <ul>
 *   <li>{@code collecte.envoi.actif} : le worker envoie la boite d'envoi (RETRY technique inclus) toutes les
 *       {@code collecte.envoi.delai-ms} ;</li>
 *   <li>{@code collecte.relance.actif} : la politique de RELANCE client s'evalue selon {@code collecte.relance.cron}
 *       (toutes les heures par defaut ; l'echeance de chaque demande, pas l'heure, decide si elle est relancee).</li>
 * </ul>
 */
@Component
public class CollecteScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollecteScheduler.class);

    private final OutboundMessageWorker worker;
    private final RelanceCollecteService relanceService;
    private final boolean envoiActif;
    private final boolean relanceActive;
    private final java.time.Clock clock;

    public CollecteScheduler(OutboundMessageWorker worker, RelanceCollecteService relanceService,
                             @Value("${collecte.envoi.actif:false}") boolean envoiActif,
                             @Value("${collecte.relance.actif:false}") boolean relanceActive, java.time.Clock clock) {
        this.clock = clock;
        this.worker = worker;
        this.relanceService = relanceService;
        this.envoiActif = envoiActif;
        this.relanceActive = relanceActive;
    }

    @Scheduled(fixedDelayString = "${collecte.envoi.delai-ms:15000}", initialDelayString = "${collecte.envoi.delai-ms:15000}")
    public void envoyer() {
        if (!envoiActif) {
            return;
        }
        int traites = worker.traiterEnAttente();
        if (traites > 0) {
            log.info("Collecte : {} message(s) traite(s)", traites);
        }
    }

    @Scheduled(cron = "${collecte.relance.cron:0 0 * * * *}")
    public void relancer() {
        if (!relanceActive) {
            return;
        }
        RelanceCollecteService.Bilan bilan = relanceService.evaluer(OffsetDateTime.now(clock));
        log.info("Collecte : relances {} ({} message(s)), {} deja recue(s), {} reportee(s), {} bloquee(s), {} expiree(s)",
                bilan.relancees(), bilan.messages(), bilan.dejaRecues(), bilan.plafonnees(), bilan.bloquees(), bilan.expirees());
    }
}
