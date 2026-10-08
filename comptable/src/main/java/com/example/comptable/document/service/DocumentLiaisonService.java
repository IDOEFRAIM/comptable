package com.example.comptable.document.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Ecriture &lt;-&gt; Piece : rattache une piece DEJA deposee a une ecriture existante (typiquement une
 * facture d'achat comptabilisee sans justificatif). Le constat {@code MISSING_DOCUMENT} correspondant
 * est ferme immediatement ; le detecteur le fermerait de toute facon au passage suivant.
 */
@Service
public class DocumentLiaisonService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final DocumentRepository documentRepository;
    private final EcritureComptableRepository ecritureRepository;
    private final PieceEcritureLinker linker;
    private final AuditTrailService auditTrailService;

    public DocumentLiaisonService(EntrepriseAccessService entrepriseAccessService, DocumentRepository documentRepository,
                                  EcritureComptableRepository ecritureRepository, PieceEcritureLinker linker,
                                  AuditTrailService auditTrailService) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.documentRepository = documentRepository;
        this.ecritureRepository = ecritureRepository;
        this.linker = linker;
        this.auditTrailService = auditTrailService;
    }

    @Transactional
    public DocumentDto lierAEcriture(UUID entrepriseId, UUID documentId, UUID ecritureId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = documentRepository.findById(documentId)
                .filter(d -> d.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        EcritureComptable ecriture = ecritureRepository.findById(ecritureId)
                .filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ecriture introuvable"));
        if (document.getEcritureId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette piece est deja rattachee a une ecriture");
        }
        if (documentRepository.findByEcritureId(ecritureId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette ecriture a deja une piece justificative");
        }
        if (!linker.lier(document, ecritureId, principal.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette ecriture a deja une piece justificative");
        }
        Document sauvegarde = document;
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "DOCUMENT_LIE_A_ECRITURE",
                "DOCUMENT", documentId.toString(), Map.of("ecritureId", ecritureId.toString(),
                        "numeroPiece", ecriture.getNumeroPiece()));
        return DocumentDto.from(sauvegarde);
    }
}
