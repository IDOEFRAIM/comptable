package com.example.comptable.agent.accounting.web;

import com.example.comptable.agent.accounting.AiAccountingDocumentQueryService;
import com.example.comptable.agent.accounting.web.dto.DocumentExtractionSnapshotDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Frontiere Python (ai-service, node load_extraction) -&gt; Java pour relire l'extraction deja
 * persistee d'un document (STEP 6 du plan d'implementation). Meme mecanisme d'authentification
 * que {@link com.example.comptable.agent.accounting.web.AiAccountingProposalValidationController}
 * et {@link com.example.comptable.agent.tools.AgentToolsInternalController} : cle partagee
 * X-Internal-Api-Key verifiee contre {@code ai-service.api-key}, exemptee du JWT applicatif
 * (meme matcher SecurityConfig que le reste de {@code /api/v1/internal/ai-accounting/**}).
 */
@RestController
@RequestMapping("/api/v1/internal/ai-accounting")
public class AiAccountingDocumentController {

    private static final Logger log = LoggerFactory.getLogger(AiAccountingDocumentController.class);

    private final AiAccountingDocumentQueryService documentQueryService;
    private final String cleApiInterne;

    public AiAccountingDocumentController(AiAccountingDocumentQueryService documentQueryService,
                                           @Value("${ai-service.api-key:}") String cleApiInterne) {
        this.documentQueryService = documentQueryService;
        this.cleApiInterne = cleApiInterne;
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<DocumentExtractionSnapshotDto> obtenirExtraction(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @PathVariable UUID documentId) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(documentQueryService.obtenirExtraction(documentId));
    }

    private boolean acceesRefuse(String cleRecue) {
        boolean refuse = cleApiInterne.isBlank() || !cleApiInterne.equals(cleRecue);
        if (refuse) {
            log.warn("Appel a la lecture d'extraction AI Accounting refuse : cle API interne absente ou invalide");
        }
        return refuse;
    }
}
