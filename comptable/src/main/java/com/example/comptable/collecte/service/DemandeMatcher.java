package com.example.comptable.collecte.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDemandeDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.PieceEcritureLinker;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Une piece recue repond-elle a une demande ouverte ? Meme philosophie que le rapprochement document/facture : regle
 * deterministe, jamais « la premiere » :
 * <ul>
 *   <li>{@code EXACT} : meme TTC, MEME tiers identifie, date a moins de 31 jours de l'operation, une seule demande ->
 *       rattachee automatiquement (demande rapprochee, constat resolu par le systeme) ;</li>
 *   <li>{@code PROBABLE} : meme TTC et (tiers identique ou date a moins de 6 jours), une seule demande ; ou la piece a ete
 *       deposee par le lien d'une demande precise (indice) -> proposee, a confirmer ;</li>
 *   <li>{@code AMBIGUOUS} : plusieurs demandes egalement plausibles -> constat, aucune resolution ;</li>
 *   <li>{@code UNMATCHED} : la piece reste a traiter normalement.</li>
 * </ul>
 */
@Component
public class DemandeMatcher {

    public enum Decision { EXACT, PROBABLE, AMBIGUOUS, UNMATCHED }

    public record Proposition(Decision decision, List<DemandePiece> candidates) {
        public DemandePiece unique() {
            return candidates.size() == 1 ? candidates.get(0) : null;
        }
    }

    static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final EnumSet<StatutDemandePiece> A_SERVIR = EnumSet.of(StatutDemandePiece.A_ENVOYER, StatutDemandePiece.EN_ATTENTE,
            StatutDemandePiece.ECHEC_ENVOI);

    private static final EnumSet<StatutDemandePiece> A_SERVIR_OU_RECUE = EnumSet.of(StatutDemandePiece.A_ENVOYER, StatutDemandePiece.EN_ATTENTE,
            StatutDemandePiece.ECHEC_ENVOI, StatutDemandePiece.RECU);

    private final DemandePieceRepository demandePieceRepository;
    private final DocumentRepository documentRepository;
    private final PieceEcritureLinker linker;
    private final AuditTrailService auditTrailService;

    public DemandeMatcher(DemandePieceRepository demandePieceRepository, DocumentRepository documentRepository,
                          PieceEcritureLinker linker, AuditTrailService auditTrailService) {
        this.demandePieceRepository = demandePieceRepository;
        this.documentRepository = documentRepository;
        this.linker = linker;
        this.auditTrailService = auditTrailService;
    }

    /** @param indice demande visee par le lien de depot utilise (peut etre {@code null}) */
    public Proposition proposer(Document document, UUID indice) {
        if (document.getTypeDocument() != TypeDocument.FACTURE_ACHAT && document.getTypeDocument() != TypeDocument.RECU) {
            return new Proposition(Decision.UNMATCHED, List.of());
        }
        // Une demande deja « recue » (piece deposee par son lien, a confirmer) reste candidate pour CETTE piece-la seulement.
        List<DemandePiece> ouvertes = demandePieceRepository.findOuvertes(document.getEntreprise().getId(), A_SERVIR_OU_RECUE).stream()
                .filter(d -> d.getEcritureId() != null)
                .filter(d -> d.getStatut() != StatutDemandePiece.RECU || d.getId().equals(indice))
                .toList();
        List<DemandePiece> exacts = new ArrayList<>();
        List<DemandePiece> probables = new ArrayList<>();
        for (DemandePiece d : ouvertes) {
            boolean montant = document.getMontantTtc() != null && d.getMontant() != null
                    && document.getMontantTtc().subtract(d.getMontant()).abs().compareTo(TOLERANCE) <= 0;
            boolean tiers = document.getTiersId() != null && document.getTiersId().equals(d.getTiersId());
            Long ecart = document.getDatePiece() != null && d.getDateOperation() != null
                    ? Math.abs(ChronoUnit.DAYS.between(document.getDatePiece(), d.getDateOperation())) : null;
            if (montant && tiers && ecart != null && ecart <= 31) {
                exacts.add(d);
            } else if (montant && (tiers || (ecart != null && ecart <= 5))) {
                probables.add(d);
            } else if (indice != null && indice.equals(d.getId())) {
                probables.add(d);
            }
        }
        if (exacts.size() == 1) {
            return new Proposition(Decision.EXACT, exacts);
        }
        if (exacts.size() > 1) {
            return new Proposition(Decision.AMBIGUOUS, exacts);
        }
        if (probables.size() == 1) {
            return new Proposition(Decision.PROBABLE, probables);
        }
        if (probables.size() > 1) {
            return new Proposition(Decision.AMBIGUOUS, probables);
        }
        return new Proposition(Decision.UNMATCHED, List.of());
    }

    /** Applique la decision a la piece recue. N'ecrit jamais d'ecriture comptable. */
    @Transactional
    public Decision appliquer(Document document, UUID indice) {
        if (document.getEcritureId() != null || document.getStatutDemande() == StatutDemandeDocument.LIEE) {
            return Decision.UNMATCHED;
        }
        Proposition p = proposer(document, indice);
        UUID tenantId = document.getTenant() != null ? document.getTenant().getId() : null;
        switch (p.decision()) {
            case EXACT -> {
                DemandePiece d = p.unique();
                if (!linker.lier(document, d.getEcritureId())) {
                    // L'ecriture est deja justifiee par une autre piece : rien n'est force.
                    return Decision.UNMATCHED;
                }
            }
            case PROBABLE -> {
                DemandePiece d = p.unique();
                document.setDemandeId(d.getId());
                document.setStatutDemande(StatutDemandeDocument.A_CONFIRMER);
                // Une vraie piece est la : la demande n'est plus « en attente du client » (plus de relance), mais reste ouverte jusqu'a confirmation.
                d.setStatut(StatutDemandePiece.RECU);
                d.setReceivedAt(OffsetDateTime.now());
                d.setProchaineRelanceAt(null);
                demandePieceRepository.save(d);
                documentRepository.save(document);
                auditTrailService.enregistrerEvenementSysteme(tenantId, "DOCUMENT_REQUEST_RECEIVED", "DEMANDE_PIECE", d.getId().toString(),
                        Map.of("documentId", document.getId().toString(), "aConfirmer", true));
            }
            case AMBIGUOUS -> {
                document.setStatutDemande(StatutDemandeDocument.AMBIGU);
                documentRepository.save(document);
            }
            case UNMATCHED -> {
            }
        }
        return p.decision();
    }
}
