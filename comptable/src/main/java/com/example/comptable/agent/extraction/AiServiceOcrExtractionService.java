package com.example.comptable.agent.extraction;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.document.ocr.OcrExtractionService;
import com.example.comptable.document.ocr.OcrResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Extraction des pieces justificatives par le microservice IA (ai-service, FastAPI), en
 * remplacement de {@link com.example.comptable.document.ocr.HeuristiqueOcrExtractionService}.
 * Actif uniquement quand {@code agent.vlm.enabled=true} : desactive par defaut, l'upload web
 * continue de passer par l'extraction heuristique sans aucun changement de comportement tant que
 * le flag n'est pas leve.
 * <p>
 * Remplace {@code VlmOcrExtractionService} (appel direct a Ollama via Spring AI, retire lors de
 * l'extraction de toute la logique IA vers ai-service) : le rendu PDF -> PNG et le prompt VLM
 * vivent desormais cote Python (cf. ai-service/app/services/extraction_service.py), portes
 * verbatim depuis l'ancienne implementation Java.
 */
@Service
@ConditionalOnProperty(name = "agent.vlm.enabled", havingValue = "true")
public class AiServiceOcrExtractionService implements OcrExtractionService {

    private final AiServiceClient aiServiceClient;

    public AiServiceOcrExtractionService(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public OcrResult extraire(byte[] contenu, String contentType) {
        String extension = switch (contentType == null ? "" : contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
        return aiServiceClient.extraire(contenu, "piece" + extension, contentType);
    }
}
