package com.example.comptable.collecte.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.collecte.delivery.DeliveryResult;
import com.example.comptable.collecte.delivery.MessageDeliveryAdapter;
import com.example.comptable.collecte.domain.CanalMessage;
import com.example.comptable.collecte.domain.OutboundMessage;
import com.example.comptable.collecte.domain.StatutMessage;
import com.example.comptable.collecte.domain.TypeMessage;
import com.example.comptable.collecte.repository.DemandeMessageRepository;
import com.example.comptable.collecte.repository.OutboundMessageRepository;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.notification.domain.TypeNotification;
import com.example.comptable.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Envoie les messages de la boite d'envoi. Le fournisseur n'est JAMAIS appele dans une transaction : un message est RESERVE
 * dans une transaction courte (verrou {@code SKIP LOCKED} : deux workers ne prennent jamais le meme), envoye hors transaction,
 * puis son resultat est ecrit dans une troisieme transaction.
 *
 * <p>Deux mecanismes distincts : le RETRY TECHNIQUE (ce message n'est pas parti : meme message, tentative suivante apres un delai) ;
 * la RELANCE CLIENT (le message est parti, le client n'a pas repondu : nouveau message, voir {@link RelanceCollecteService}).</p>
 */
@Component
public class OutboundMessageWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboundMessageWorker.class);
    private static final int MINUTES_AVANT_LIBERATION = 10;
    private static final int ECHECS_DE_RELANCE_MAX = 3;

    private final OutboundMessageRepository messageRepository;
    private final DemandeMessageRepository demandeMessageRepository;
    private final DemandePieceRepository demandePieceRepository;
    private final List<MessageDeliveryAdapter> adaptateurs;
    private final CollecteConfig config;
    private final AuditTrailService auditTrailService;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;

    /**
     * Mode bac à sable (staging, formation, démonstration) : si cette liste de numéros (chiffres, séparés par des virgules) n'est pas vide, AUCUN message n'est remis à un autre destinataire. Vide (défaut) : pas de
     * restriction. Empêche l'envoi accidentel de messages de test à de vrais clients.
     */
    @org.springframework.beans.factory.annotation.Value("${collecte.sandbox.allowlist:}")
    private String sandboxAllowlist = "";
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "collecte-envoi");
        t.setDaemon(true);
        return t;
    });

    public OutboundMessageWorker(OutboundMessageRepository messageRepository, DemandeMessageRepository demandeMessageRepository,
                                 DemandePieceRepository demandePieceRepository, List<MessageDeliveryAdapter> adaptateurs,
                                 CollecteConfig config, AuditTrailService auditTrailService, NotificationService notificationService,
                                 PlatformTransactionManager transactionManager) {
        this.messageRepository = messageRepository;
        this.demandeMessageRepository = demandeMessageRepository;
        this.demandePieceRepository = demandePieceRepository;
        this.adaptateurs = adaptateurs;
        this.config = config;
        this.auditTrailService = auditTrailService;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Declenche un passage sans bloquer l'appelant (action « Envoyer » du comptable). */
    public void declencherAsync() {
        executor.submit(() -> {
            try {
                traiterEnAttente();
            } catch (RuntimeException e) {
                log.error("Passage d'envoi en echec : {}", e.toString());
            }
        });
    }

    /** @return le nombre de messages traites (envoyes, reessayes, echoues ou annules). */
    public int traiterEnAttente() {
        List<UUID> ids = tx.execute(statut -> {
            messageRepository.liberer(OffsetDateTime.now().minusMinutes(MINUTES_AVANT_LIBERATION));
            return messageRepository.reserver(config.taillePaquetEnvoi());
        });
        int traites = 0;
        for (UUID id : ids != null ? ids : List.<UUID>of()) {
            try {
                traiter(id);
                traites++;
            } catch (RuntimeException e) {
                // Jamais un message bloque en SENDING : il sera libere puis reessaye.
                log.error("Message {} : traitement interrompu : {}", id, e.toString());
            }
        }
        return traites;
    }

    /** Envoie UN message deja reserve ({@code SENDING}). */
    void traiter(UUID messageId) {
        Preparation preparation = tx.execute(statut -> preparer(messageId));
        if (preparation == null) {
            return;
        }
        DeliveryResult resultat;
        if (preparation.message == null) {
            return; // annule : plus rien a servir
        }
        if (!destinataireAutorise(sandboxAllowlist, preparation.message.getRecipient())) {
            DeliveryResult refus = DeliveryResult.definitif("SANDBOX : destinataire hors liste autorisée (collecte.sandbox.allowlist) ; aucun message envoyé");
            tx.executeWithoutResult(statut -> finaliser(messageId, refus));
            return;
        }
        CanalMessage canal = preparation.message.getChannel();
        MessageDeliveryAdapter adaptateur = adaptateurs.stream().filter(a -> a.canal() == canal).findFirst().orElse(null);
        resultat = adaptateur != null ? adaptateur.send(preparation.message)
                : DeliveryResult.definitif("Aucun adaptateur d'envoi pour le canal " + preparation.message.getChannel());
        tx.executeWithoutResult(statut -> finaliser(messageId, resultat));
    }

    private record Preparation(OutboundMessage message) {
    }

    /** Vrai si aucune liste bac à sable n'est configurée, ou si le destinataire y figure (comparaison sur les chiffres). */
    public static boolean destinataireAutorise(String sandboxAllowlist, String destinataire) {
        if (sandboxAllowlist == null || sandboxAllowlist.isBlank()) {
            return true;
        }
        String chiffres = destinataire == null ? "" : destinataire.replaceAll("[^0-9]", "");
        for (String autorise : sandboxAllowlist.split(",")) {
            if (!autorise.isBlank() && autorise.replaceAll("[^0-9]", "").equals(chiffres)) {
                return true;
            }
        }
        return false;
    }

    /** Recalcule l'etat reel : une demande deja recue, annulee ou rapprochee n'est plus servie ; s'il n'en reste aucune, le message est annule. */
    @SuppressWarnings("unchecked")
    private Preparation preparer(UUID messageId) {
        OutboundMessage message = messageRepository.findById(messageId).orElse(null);
        if (message == null || message.getStatus() != StatutMessage.SENDING) {
            return null;
        }
        Set<UUID> actives = demandesActives(message).stream().map(DemandePiece::getId).collect(Collectors.toSet());
        if (actives.isEmpty()) {
            message.setStatus(StatutMessage.CANCELLED);
            message.setLockedAt(null);
            message.setLastError("Plus aucune demande ouverte a servir");
            messageRepository.save(message);
            return new Preparation(null);
        }
        if (message.getPayload().get("elements") instanceof List<?> elements) {
            List<Map<String, Object>> gardes = new ArrayList<>();
            for (Object e : elements) {
                Map<String, Object> element = (Map<String, Object>) e;
                if (actives.contains(UUID.fromString(String.valueOf(element.get("demandeId"))))) {
                    gardes.add(element);
                }
            }
            if (gardes.size() != elements.size()) {
                Map<String, Object> payload = new HashMap<>(message.getPayload());
                payload.put("elements", gardes);
                message.setPayload(payload);
                messageRepository.save(message);
            }
        }
        return new Preparation(message);
    }

    private List<DemandePiece> demandesActives(OutboundMessage message) {
        StatutDemandePiece attendu = message.getKind() == TypeMessage.INITIAL ? StatutDemandePiece.A_ENVOYER : StatutDemandePiece.EN_ATTENTE;
        return demandePieceRepository.findAllById(demandeMessageRepository.demandeIdsDuMessage(message.getId())).stream()
                .filter(d -> d.getStatut() == attendu)
                .toList();
    }

    private void finaliser(UUID messageId, DeliveryResult resultat) {
        OutboundMessage message = messageRepository.findById(messageId).orElseThrow();
        List<DemandePiece> demandes = demandesActives(message);
        OffsetDateTime maintenant = OffsetDateTime.now();
        message.setLockedAt(null);
        switch (resultat.issue()) {
            case SENT -> {
                message.setStatus(StatutMessage.SENT);
                message.setSentAt(maintenant);
                message.setProviderMessageId(resultat.providerMessageId());
                message.setLastError(null);
                message.setErrorKind(null);
                demandes.forEach(d -> apresEnvoi(d, message, maintenant));
            }
            case RETRYABLE -> {
                message.setLastError(resultat.erreur());
                message.setErrorKind("RETRYABLE");
                if (message.getAttemptCount() < message.getMaxAttempts()) {
                    // RETRY TECHNIQUE : meme message, tentative suivante plus tard. Aucun constat, aucune demande modifiee.
                    message.setStatus(StatutMessage.PENDING);
                    message.setNextAttemptAt(maintenant.plusMinutes(config.backoffMinutes(message.getAttemptCount())));
                } else {
                    echouer(message, demandes, maintenant, "SEND_FAILED");
                }
            }
            case PERMANENT -> {
                message.setLastError(resultat.erreur());
                message.setErrorKind("PERMANENT");
                echouer(message, demandes, maintenant, "INVALID_CONTACT");
            }
        }
        messageRepository.save(message);
    }

    private void apresEnvoi(DemandePiece d, OutboundMessage message, OffsetDateTime maintenant) {
        if (message.getKind() == TypeMessage.INITIAL) {
            d.setStatut(StatutDemandePiece.EN_ATTENTE);
            d.setSentAt(maintenant);
            d.setMotifBlocage(null);
            d.setProchaineRelanceAt(config.delaiAvantRelance(0).isPresent() ? maintenant.plusDays(config.delaiAvantRelance(0).getAsInt()) : null);
            auditer(d, "DOCUMENT_REQUEST_SENT", Map.of("messageId", message.getId().toString(), "canal", message.getChannel().name()));
        } else {
            d.setDerniereRelanceAt(maintenant);
            d.setNbRelances(d.getNbRelances() + 1);
            d.setProchaineRelanceAt(config.delaiAvantRelance(d.getNbRelances()).isPresent()
                    ? maintenant.plusDays(config.delaiAvantRelance(d.getNbRelances()).getAsInt()) : null);
            auditer(d, "DOCUMENT_REQUEST_REMINDER_SENT", Map.of("messageId", message.getId().toString(), "numeroRelance", d.getNbRelances()));
        }
        demandePieceRepository.save(d);
    }

    private void echouer(OutboundMessage message, List<DemandePiece> demandes, OffsetDateTime maintenant, String motif) {
        message.setStatus(StatutMessage.FAILED);
        message.setFailedAt(maintenant);
        for (DemandePiece d : demandes) {
            if (message.getKind() == TypeMessage.INITIAL) {
                d.setStatut(StatutDemandePiece.ECHEC_ENVOI);
                d.setMotifBlocage(motif);
            } else {
                long echecs = messageRepository.findParDemande(d.getId()).stream()
                        .filter(m -> m.getKind() == TypeMessage.REMINDER && m.getStatus() == StatutMessage.FAILED).count() + 1;
                if (echecs >= ECHECS_DE_RELANCE_MAX) {
                    d.setStatut(StatutDemandePiece.ECHEC_ENVOI);
                    d.setMotifBlocage(motif);
                } else {
                    // Une relance qui n'est pas partie sera retentee demain (nouveau message) : pas d'anomalie metier a chaque tentative.
                    d.setProchaineRelanceAt(maintenant.plusDays(1));
                }
            }
            demandePieceRepository.save(d);
            auditer(d, "DOCUMENT_REQUEST_SEND_FAILED", Map.of("messageId", message.getId().toString(), "motif", motif,
                    "erreur", String.valueOf(message.getLastError())));
            if (d.getStatut() == StatutDemandePiece.ECHEC_ENVOI && d.getDemandeePar() != null) {
                notificationService.notifier(d.getDemandeePar(), TypeNotification.RELANCE_PIECE,
                        "Demande de piece non envoyee (" + d.getEntreprise().getRaisonSociale() + ") : " + libelleMotif(motif));
            }
        }
    }

    static String libelleMotif(String motif) {
        return switch (motif) {
            case "NO_CONTACT" -> "aucun contact client";
            case "INVALID_CONTACT" -> "numero WhatsApp refuse ou invalide";
            default -> "echec d'envoi apres plusieurs tentatives";
        };
    }

    private void auditer(DemandePiece d, String action, Map<String, Object> details) {
        Map<String, Object> enrichis = new HashMap<>(details);
        enrichis.put("entrepriseId", d.getEntreprise().getId().toString());
        auditTrailService.enregistrerEvenementSysteme(d.getEntreprise().getTenant() != null ? d.getEntreprise().getTenant().getId() : null,
                action, "DEMANDE_PIECE", d.getId().toString(), enrichis);
    }
}
