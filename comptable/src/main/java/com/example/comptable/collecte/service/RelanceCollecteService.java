package com.example.comptable.collecte.service;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.collecte.domain.OutboundMessage;
import com.example.comptable.collecte.domain.TypeMessage;
import com.example.comptable.collecte.repository.OutboundMessageRepository;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RELANCE CLIENT (le message est parti, le client n'a pas repondu) selon une politique configurable
 * ({@link CollecteConfig#delaisRelanceJours()}). Avant chaque relance l'etat REEL est recalcule : une piece arrivee ou une demande
 * close ne produit jamais de relance ; plafond de messages par destinataire et par jour (jamais de spam) ; les relances d'un meme
 * client sont regroupees en un seul message. Passe la main a un humain quand la politique est epuisee.
 */
@Service
public class RelanceCollecteService {

    static final int LOT = 200;

    public record Bilan(int relancees, int messages, int dejaRecues, int plafonnees, int bloquees, int expirees) {
    }

    private final DemandePieceRepository demandePieceRepository;
    private final DocumentRepository documentRepository;
    private final OutboundMessageRepository messageRepository;
    private final ContactResolver contactResolver;
    private final CollecteConfig config;
    private final CollecteService collecteService;
    private final FindingService findingService;
    private final AuditTrailService auditTrailService;
    private final OutboundMessageWorker worker;

    public RelanceCollecteService(DemandePieceRepository demandePieceRepository, DocumentRepository documentRepository,
                                  OutboundMessageRepository messageRepository, ContactResolver contactResolver, CollecteConfig config,
                                  CollecteService collecteService, FindingService findingService, AuditTrailService auditTrailService,
                                  OutboundMessageWorker worker) {
        this.demandePieceRepository = demandePieceRepository;
        this.documentRepository = documentRepository;
        this.messageRepository = messageRepository;
        this.contactResolver = contactResolver;
        this.config = config;
        this.collecteService = collecteService;
        this.findingService = findingService;
        this.auditTrailService = auditTrailService;
        this.worker = worker;
    }

    /** Un passage de la politique de relance sur TOUS les dossiers (tache planifiee). */
    @Transactional
    public Bilan evaluer(OffsetDateTime maintenant) {
        return evaluer(maintenant, null);
    }

    /**
     * Un passage de la politique de relance, eventuellement restreint a UN dossier ({@code entrepriseId}) : l'evaluation ne lit, ne modifie et ne compte alors que les demandes de ce dossier, jamais celles des
     * autres (pas de dependance a l'etat global de la base). {@code maintenant} est l'horloge du passage (simulee par les tests).
     */
    @Transactional
    public Bilan evaluer(OffsetDateTime maintenant, UUID entrepriseId) {
        int dejaRecues = 0;
        int plafonnees = 0;
        int bloquees = 0;
        int relancees = 0;
        int messages = 0;

        Map<UUID, List<DemandePiece>> aRelancerParEntreprise = new LinkedHashMap<>();
        Map<UUID, Entreprise> entreprises = new LinkedHashMap<>();
        List<DemandePiece> dues = entrepriseId == null ? demandePieceRepository.findARelancer(maintenant, PageRequest.of(0, LOT))
                : demandePieceRepository.findARelancerPourEntreprise(entrepriseId, maintenant, PageRequest.of(0, LOT));
        for (DemandePiece d : dues) {
            // Etat reel : une piece est-elle deja la (ou attend-elle une decision du comptable) ? Alors aucune relance.
            if (pieceDejaArrivee(d)) {
                cloturerCarRecue(d, maintenant);
                dejaRecues++;
                continue;
            }
            if (d.getMontant() != null && documentRepository.existsAmbiguPourMontant(d.getEntreprise().getId(), d.getMontant())) {
                d.setProchaineRelanceAt(maintenant.plusDays(1));
                demandePieceRepository.save(d);
                plafonnees++;
                continue;
            }
            aRelancerParEntreprise.computeIfAbsent(d.getEntreprise().getId(), k -> new ArrayList<>()).add(d);
            entreprises.put(d.getEntreprise().getId(), d.getEntreprise());
        }

        for (Map.Entry<UUID, List<DemandePiece>> e : aRelancerParEntreprise.entrySet()) {
            Entreprise entreprise = entreprises.get(e.getKey());
            ContactResolver.Resolution contacts = contactResolver.resoudre(entreprise.getId());
            if (!contacts.utilisable()) {
                for (DemandePiece d : e.getValue()) {
                    d.setStatut(StatutDemandePiece.ECHEC_ENVOI);
                    d.setMotifBlocage(contacts.motif());
                    demandePieceRepository.save(d);
                    bloquees++;
                }
                continue;
            }
            // Plafond quotidien par destinataire : au-dela, on reporte a demain (jamais N messages le meme jour).
            boolean plafond = contacts.contacts().stream().anyMatch(c -> messageRepository
                    .countByEntrepriseIdAndRecipientAndCreatedAtAfter(entreprise.getId(), c.telephone(), maintenant.minusDays(1))
                    >= config.maxMessagesParJour());
            if (plafond) {
                for (DemandePiece d : e.getValue()) {
                    d.setProchaineRelanceAt(maintenant.plusDays(1));
                    demandePieceRepository.save(d);
                    plafonnees++;
                }
                continue;
            }
            List<OutboundMessage> crees = collecteService.enfiler(entreprise, e.getValue(), TypeMessage.REMINDER);
            relancees += e.getValue().size();
            messages += crees.size();
        }

        int expirees = expirer(maintenant, entrepriseId);
        if (messages > 0 && config.envoiImmediat()) {
            worker.declencherAsync();
        }
        return new Bilan(relancees, messages, dejaRecues, plafonnees, bloquees, expirees);
    }

    /** Une demande sans reponse apres {@code collecte.expiration-jours} expire : elle sort de la collecte automatique. */
    private int expirer(OffsetDateTime maintenant, UUID entrepriseId) {
        int n = 0;
        OffsetDateTime avant = maintenant.minusDays(config.expirationJours());
        for (DemandePiece d : entrepriseId == null ? demandePieceRepository.findAExpirer(avant) : demandePieceRepository.findAExpirerPourEntreprise(entrepriseId, avant)) {
            d.setStatut(StatutDemandePiece.EXPIREE);
            d.setResolueAt(maintenant);
            d.setProchaineRelanceAt(null);
            demandePieceRepository.save(d);
            auditTrailService.enregistrerEvenementSysteme(d.getEntreprise().getTenant() != null ? d.getEntreprise().getTenant().getId() : null,
                    "DOCUMENT_REQUEST_EXPIRED", "DEMANDE_PIECE", d.getId().toString(), Map.of("jours", config.expirationJours()));
            n++;
        }
        return n;
    }

    /** L'operation est-elle deja justifiee par une piece (directe ou rapprochee a sa facture) ? */
    private boolean pieceDejaArrivee(DemandePiece d) {
        if (d.getEcritureId() == null) {
            return false;
        }
        return documentRepository.findByEcritureId(d.getEcritureId()).isPresent()
                || !documentRepository.findViaFactureFournisseur(d.getEntreprise().getId(), d.getEcritureId()).isEmpty();
    }

    private void cloturerCarRecue(DemandePiece d, OffsetDateTime maintenant) {
        d.setStatut(StatutDemandePiece.RAPPROCHEE);
        d.setReceivedAt(d.getReceivedAt() != null ? d.getReceivedAt() : maintenant);
        d.setResolueAt(maintenant);
        d.setResoluPar("SYSTEME");
        d.setProchaineRelanceAt(null);
        demandePieceRepository.save(d);
        findingService.resoudreParControle(d.getEntreprise().getId(), ControlCodes.MISSING_DOCUMENT, "ECRITURE_COMPTABLE", d.getEcritureId().toString());
        UUID tenantId = d.getEntreprise().getTenant() != null ? d.getEntreprise().getTenant().getId() : null;
        auditTrailService.enregistrerEvenementSysteme(tenantId, "DOCUMENT_REQUEST_RESOLVED", "DEMANDE_PIECE", d.getId().toString(),
                Map.of("origine", "SYSTEME", "motif", "piece deja arrivee avant la relance", "montant", String.valueOf(d.getMontant() != null ? d.getMontant() : BigDecimal.ZERO)));
    }
}
