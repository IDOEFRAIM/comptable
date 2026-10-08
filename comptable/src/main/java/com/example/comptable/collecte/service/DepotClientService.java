package com.example.comptable.collecte.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDemandeDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.DocumentService;
import com.example.comptable.document.web.dto.DocumentDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Depot d'une piece par le client via le lien de sa demande : sans compte, sans portail. Le lien ne donne acces qu'a UNE chose :
 * deposer un fichier dans le dossier de SA entreprise, tant que la demande est ouverte et le lien non expire. Le fichier suit le
 * pipeline normal (hash, extraction, doublons) puis le rapprochement aux demandes ; le lien sert d'INDICE (jamais de decision) :
 * une piece deposee par ce lien est au minimum proposee a la demande, et ne resout rien sans certitude.
 */
@Service
public class DepotClientService {

    /** Ce que la page de depot montre au client : strictement de quoi comprendre ce qu'on lui demande. */
    public record Informations(String entreprise, String description, BigDecimal montant, LocalDate date, LocalDate dateLimite) {
    }

    private final DepotLienService depotLienService;
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final DemandeMatcher demandeMatcher;
    private final DemandePieceRepository demandePieceRepository;
    private final AuditTrailService auditTrailService;

    public DepotClientService(DepotLienService depotLienService, DocumentService documentService, DocumentRepository documentRepository,
                        DemandeMatcher demandeMatcher, DemandePieceRepository demandePieceRepository, AuditTrailService auditTrailService) {
        this.depotLienService = depotLienService;
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.demandeMatcher = demandeMatcher;
        this.demandePieceRepository = demandePieceRepository;
        this.auditTrailService = auditTrailService;
    }

    @Transactional(readOnly = true)
    public Informations informations(String jeton) {
        DemandePiece d = depotLienService.verifier(jeton).orElseThrow(DepotClientService::introuvable);
        return new Informations(d.getEntreprise().getRaisonSociale(),
                d.getDescriptionOperation() != null ? d.getDescriptionOperation() : d.getLibelle(), d.getMontant(), d.getDateOperation(), d.getDateLimite());
    }

    @Transactional
    public void deposer(String jeton, MultipartFile fichier) {
        DemandePiece demande = depotLienService.verifier(jeton).orElseThrow(DepotClientService::introuvable);
        byte[] contenu;
        try {
            contenu = fichier.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier illisible");
        }
        DocumentDto dto = documentService.ingererDepuisCanalExterne(demande.getEntreprise().getId(), TypeDocument.FACTURE_ACHAT, CanalSource.WEB,
                contenu, fichier.getOriginalFilename() != null ? fichier.getOriginalFilename() : "piece", fichier.getContentType());
        Document document = documentRepository.findById(dto.id()).orElseThrow();
        if (document.getStatutDemande() == StatutDemandeDocument.AUCUNE) {
            demandeMatcher.appliquer(document, demande.getId());
        }
        // Une vraie piece est arrivee : on cesse de relancer, la demande reste ouverte jusqu'a rattachement.
        DemandePiece courante = demandePieceRepository.findById(demande.getId()).orElseThrow();
        if (courante.getStatut() == StatutDemandePiece.EN_ATTENTE || courante.getStatut() == StatutDemandePiece.A_ENVOYER) {
            courante.setStatut(StatutDemandePiece.RECU);
            courante.setReceivedAt(OffsetDateTime.now());
            courante.setProchaineRelanceAt(null);
            demandePieceRepository.save(courante);
            auditTrailService.enregistrerEvenementSysteme(courante.getEntreprise().getTenant() != null ? courante.getEntreprise().getTenant().getId() : null,
                    "DOCUMENT_REQUEST_RECEIVED", "DEMANDE_PIECE", courante.getId().toString(), Map.of("documentId", document.getId().toString(), "canal", "LIEN"));
        }
    }

    /** Toujours la meme reponse (404) pour un jeton forge, inconnu, expire ou une demande close : aucune information ne fuit. */
    private static ResponseStatusException introuvable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Lien invalide ou expire");
    }
}
