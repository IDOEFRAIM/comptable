package com.example.comptable.agent.accounting.web;

import com.example.comptable.agent.accounting.AiAccountingProposalValidationService;
import com.example.comptable.agent.accounting.web.dto.AiAccountingValidationResponseDto;
import com.example.comptable.agent.accounting.web.dto.ValidateAiAccountingProposalRequest;
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
 * Frontiere Python (ai-service, workflow LangGraph AI Accounting) -&gt; Java pour la validation
 * d'un brouillon de proposition comptable (STEP 4 du plan d'implementation). Meme mecanisme
 * d'authentification que {@link com.example.comptable.agent.tools.AgentToolsInternalController} :
 * cle partagee X-Internal-Api-Key verifiee contre {@code ai-service.api-key}, exemptee du JWT
 * applicatif dans SecurityConfig - jamais appele par un utilisateur final. Ne poste jamais rien,
 * voir {@link AiAccountingProposalValidationService}.
 */
@RestController
@RequestMapping("/api/v1/internal/ai-accounting")
public class AiAccountingProposalValidationController {

    private static final Logger log = LoggerFactory.getLogger(AiAccountingProposalValidationController.class);

    private final AiAccountingProposalValidationService validationService;
    private final String cleApiInterne;

    public AiAccountingProposalValidationController(AiAccountingProposalValidationService validationService,
                                                      @Value("${ai-service.api-key:}") String cleApiInterne) {
        this.validationService = validationService;
        this.cleApiInterne = cleApiInterne;
    }

    @PostMapping("/proposals/validate")
    public ResponseEntity<AiAccountingValidationResponseDto> validerProposition(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @Valid @RequestBody ValidateAiAccountingProposalRequest requete) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(validationService.valider(requete));
    }

    private boolean acceesRefuse(String cleRecue) {
        boolean refuse = cleApiInterne.isBlank() || !cleApiInterne.equals(cleRecue);
        if (refuse) {
            log.warn("Appel a la validation AI Accounting refuse : cle API interne absente ou invalide");
        }
        return refuse;
    }
}
