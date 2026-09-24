package com.example.comptable.agent.accounting.web;

import com.example.comptable.agent.accounting.AiAccountingPostingService;
import com.example.comptable.agent.accounting.web.dto.AiAccountingPostingResponseDto;
import com.example.comptable.agent.accounting.web.dto.PostAiAccountingProposalRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontiere Python (ai-service, MCP tool accounting.post_entry) -&gt; Java pour poster reellement
 * une proposition comptable (STEP 7 du plan d'implementation) - la premiere operation mutative
 * importante de ce workflow. Meme mecanisme d'authentification que
 * {@link com.example.comptable.agent.accounting.web.AiAccountingProposalValidationController} :
 * cle partagee X-Internal-Api-Key, exemptee du JWT applicatif. Ne fait qu'orchestrer l'appel a
 * {@link AiAccountingPostingService} (qui delegue integralement a AccountingOperationEngine) -
 * aucune logique de posting dans ce controller.
 */
@RestController
@RequestMapping("/api/v1/internal/ai-accounting")
public class AiAccountingPostingController {

    private static final Logger log = LoggerFactory.getLogger(AiAccountingPostingController.class);

    private final AiAccountingPostingService postingService;
    private final String cleApiInterne;

    public AiAccountingPostingController(AiAccountingPostingService postingService,
                                          @Value("${ai-service.api-key:}") String cleApiInterne) {
        this.postingService = postingService;
        this.cleApiInterne = cleApiInterne;
    }

    @PostMapping("/proposals/post")
    public ResponseEntity<AiAccountingPostingResponseDto> posterProposition(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @Valid @RequestBody PostAiAccountingProposalRequest requete) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(postingService.poster(requete));
    }

    private boolean acceesRefuse(String cleRecue) {
        boolean refuse = cleApiInterne.isBlank() || !cleApiInterne.equals(cleRecue);
        if (refuse) {
            log.warn("Appel au posting AI Accounting refuse : cle API interne absente ou invalide");
        }
        return refuse;
    }
}
