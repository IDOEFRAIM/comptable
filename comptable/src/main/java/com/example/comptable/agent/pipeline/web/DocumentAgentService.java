package com.example.comptable.agent.pipeline.web;

import com.example.comptable.agent.pipeline.PieceJustificativeAgentService;
import com.example.comptable.agent.pipeline.web.dto.DocumentEnAttenteValidationDto;
import com.example.comptable.agent.pipeline.web.dto.ValiderDocumentRequest;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Couche service de l'inbox de validation (Sprint 4) : lecture des pieces en pause au noeud
 * {@code attente_validation}, et validation/reprise. Ne comptabilise jamais directement - la
 * comptabilisation (choix du tiers et du compte, action deliberee par piece) reste l'action
 * separee et deja existante {@code POST .../documents/{id}/comptabiliser}
 * ({@link com.example.comptable.comptabilite.service.ComptabilisationService#comptabiliser}) :
 * fondre les deux dans un seul clic de validation par lot casserait l'ergonomie "zero friction"
 * demandee, en forçant un choix de tiers/compte pour chaque piece d'un lot.
 */
@Service
@RequiredArgsConstructor
public class DocumentAgentService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final DocumentRepository documentRepository;
    private final PieceJustificativeAgentService pieceJustificativeAgentService;

    @Transactional(readOnly = true)
    public List<DocumentEnAttenteValidationDto> listerEnAttenteValidation(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return documentRepository
                .findByEntrepriseIdAndStatutOrderByCreatedAtDesc(entrepriseId, StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE)
                .stream()
                .map(DocumentEnAttenteValidationDto::from)
                .toList();
    }

    /** Persiste les corrections eventuelles, puis reprend le graphe en pause pour ce document.
     * La reprise (via {@link PieceJustificativeAgentService}) marque {@code valideParHumain=true}
     * et tente une comptabilisation automatique qui, aujourd'hui, aboutit rarement (cf. javadoc
     * d'{@link com.example.comptable.agent.pipeline.EcritureNode}) - la piece reste alors visible
     * dans l'ecran Documents classique pour une comptabilisation manuelle avec tiers/compte. */
    @Transactional
    public DocumentDto validerEtReprendre(UUID entrepriseId, UUID documentId, ValiderDocumentRequest request,
                                           UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = documentRepository.findById(documentId)
                .filter(d -> d.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        if (document.getStatut() != StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce document n'est pas en attente de validation (statut actuel : " + document.getStatut() + ")");
        }

        appliquerCorrections(document, request);
        // "Valider" doit toujours lever la revue humaine, quel que soit le canal d'origine : la
        // reprise du graphe ci-dessous ne remet le statut a jour QUE pour les pieces qui ont
        // reellement un checkpoint en pause (canal WhatsApp aujourd'hui, cf. AnalyseFraudeNode) -
        // sans cette ligne, un document uploade via le web et bascule en EN_ATTENTE_VALIDATION_HUMAINE
        // par un score de fraude CRITIQUE (Sprint 7) resterait bloque indefiniment dans l'inbox,
        // faute de graphe a reprendre pour le faire ressortir de ce statut.
        document.setStatut(StatutDocument.TRAITE);
        documentRepository.save(document);

        pieceJustificativeAgentService.reprendreApresValidation(documentId);

        Document documentApresReprise = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        return DocumentDto.from(documentApresReprise);
    }

    private void appliquerCorrections(Document document, ValiderDocumentRequest request) {
        if (request.montantHt() != null) {
            document.setMontantHt(request.montantHt());
        }
        if (request.montantTva() != null) {
            document.setMontantTva(request.montantTva());
        }
        if (request.montantTtc() != null) {
            document.setMontantTtc(request.montantTtc());
        }
        if (request.datePiece() != null) {
            document.setDatePiece(request.datePiece());
        }
    }
}
