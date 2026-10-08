package com.example.comptable.document.service;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDemandeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Point UNIQUE ou une piece devient le justificatif d'une ecriture : le lien {@code Document.ecritureId}, la fermeture
 * du constat {@code MISSING_DOCUMENT} et la cloture des demandes de piece qui reclamaient ce justificatif. Les deux
 * entrees (rattachement manuel, rapprochement a une facture) passent ici : une seule facon de faire.
 *
 * <p>Poser {@code ecritureId} a aussi un role de garde : une piece rattachee a une facture DEJA comptabilisee ne peut plus
 * etre comptabilisee a part (le flux Documents refuse « deja comptabilise »), donc pas de double ecriture.</p>
 */
@Component
public class PieceEcritureLinker {

    private final DocumentRepository documentRepository;
    private final FindingService findingService;
    private final DemandePieceRepository demandePieceRepository;
    private final AuditTrailService auditTrailService;

    public PieceEcritureLinker(DocumentRepository documentRepository, FindingService findingService,
                               DemandePieceRepository demandePieceRepository, AuditTrailService auditTrailService) {
        this.documentRepository = documentRepository;
        this.findingService = findingService;
        this.demandePieceRepository = demandePieceRepository;
        this.auditTrailService = auditTrailService;
    }

    /** Lien decide par le SYSTEME (certitude). */
    @Transactional
    public boolean lier(Document document, UUID ecritureId) {
        return lier(document, ecritureId, null);
    }

    /**
     * @param utilisateurId l'utilisateur qui decide (association manuelle) : le constat est alors resolu par lui
     *                      ({@code MANUAL}) ; {@code null} = decision automatique du systeme ({@code SYSTEM}).
     * @return {@code false} si une autre piece est deja le justificatif de cette ecriture (rien n'est modifie).
     */
    @Transactional
    public boolean lier(Document document, UUID ecritureId, UUID utilisateurId) {
        if (document.getEcritureId() != null && document.getEcritureId().equals(ecritureId)) {
            fermerLesSuites(document, ecritureId, utilisateurId);
            return true;
        }
        if (document.getEcritureId() != null || documentRepository.findByEcritureId(ecritureId).isPresent()) {
            return false;
        }
        document.setEcritureId(ecritureId);
        documentRepository.save(document);
        fermerLesSuites(document, ecritureId, utilisateurId);
        return true;
    }

    private void fermerLesSuites(Document document, UUID ecritureId, UUID utilisateurId) {
        UUID entrepriseId = document.getEntreprise().getId();
        if (utilisateurId != null) {
            findingService.resoudreParControleManuellement(entrepriseId, ControlCodes.MISSING_DOCUMENT, "ECRITURE_COMPTABLE",
                    ecritureId.toString(), utilisateurId, "Piece rattachee par le comptable");
        } else {
            findingService.resoudreParControle(entrepriseId, ControlCodes.MISSING_DOCUMENT, "ECRITURE_COMPTABLE", ecritureId.toString());
        }
        UUID tenantId = document.getTenant() != null ? document.getTenant().getId() : null;
        OffsetDateTime maintenant = OffsetDateTime.now();
        List<DemandePiece> demandes = demandePieceRepository.findByEntrepriseIdAndEcritureIdAndStatutIn(entrepriseId, ecritureId,
                List.of(StatutDemandePiece.A_ENVOYER, StatutDemandePiece.EN_ATTENTE, StatutDemandePiece.RECU, StatutDemandePiece.ECHEC_ENVOI));
        for (DemandePiece demande : demandes) {
            demande.setStatut(StatutDemandePiece.RAPPROCHEE);
            demande.setDocumentId(document.getId());
            demande.setReceivedAt(demande.getReceivedAt() != null ? demande.getReceivedAt() : maintenant);
            demande.setResolueAt(maintenant);
            demande.setResoluPar(utilisateurId != null ? "HUMAIN" : "SYSTEME");
            demande.setProchaineRelanceAt(null);
            demandePieceRepository.save(demande);
            document.setDemandeId(demande.getId());
            document.setStatutDemande(StatutDemandeDocument.LIEE);
            documentRepository.save(document);
            Map<String, Object> details = Map.of("documentId", document.getId().toString(), "ecritureId", ecritureId.toString(),
                    "origine", utilisateurId != null ? "HUMAIN" : "SYSTEME");
            // DOCUMENT_RECU_APRES_DEMANDE : evenement historique conserve ; les trois suivants decrivent le cycle de la demande.
            auditTrailService.enregistrerEvenementSysteme(tenantId, "DOCUMENT_RECU_APRES_DEMANDE", "DEMANDE_PIECE", demande.getId().toString(), details);
            for (String action : List.of("DOCUMENT_REQUEST_RECEIVED", "DOCUMENT_REQUEST_MATCHED", "DOCUMENT_REQUEST_RESOLVED")) {
                if (utilisateurId != null) {
                    auditTrailService.enregistrerActionHumaine(document.getTenant() != null ? tenantId : null, utilisateurId, action, "DEMANDE_PIECE",
                            demande.getId().toString(), details);
                } else {
                    auditTrailService.enregistrerEvenementSysteme(tenantId, action, "DEMANDE_PIECE", demande.getId().toString(), details);
                }
            }
        }
    }
}
