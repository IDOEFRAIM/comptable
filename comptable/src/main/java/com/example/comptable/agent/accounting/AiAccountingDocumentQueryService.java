package com.example.comptable.agent.accounting;

import com.example.comptable.agent.accounting.web.dto.DocumentExtractionSnapshotDto;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Lecture seule de l'extraction deja persistee d'un document (STEP 6 du plan d'implementation) -
 * pour le node Python load_extraction. Ne relance jamais d'extraction, ne modifie jamais rien :
 * relit {@link Document} exactement comme le fait deja
 * {@code agent.pipeline.ExtractionNode} cote LangGraph4j (meme principe, cote Python cette fois).
 */
@Service
@RequiredArgsConstructor
public class AiAccountingDocumentQueryService {

    private final DocumentRepository documentRepository;

    public DocumentExtractionSnapshotDto obtenirExtraction(UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));

        Object fournisseur = valeurOcr(document, "fournisseur");
        Object devise = valeurOcr(document, "devise");
        Object modePaiement = valeurOcr(document, "modePaiement");
        Object confiance = valeurOcr(document, "confiance");

        return new DocumentExtractionSnapshotDto(
                document.getId(),
                document.getEntreprise().getId(),
                document.getTypeDocument(),
                document.getMontantHt(),
                document.getMontantTva(),
                document.getMontantTtc(),
                document.getDatePiece(),
                fournisseur != null ? fournisseur.toString() : null,
                devise != null ? devise.toString() : "XOF",
                modePaiement != null ? modePaiement.toString() : null,
                confiance instanceof Number nombre ? nombre.doubleValue() : null
        );
    }

    private Object valeurOcr(Document document, String cle) {
        return document.getDonneesOcr() != null ? document.getDonneesOcr().get(cle) : null;
    }
}
