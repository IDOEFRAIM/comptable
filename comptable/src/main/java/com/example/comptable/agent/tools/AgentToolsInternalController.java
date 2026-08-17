package com.example.comptable.agent.tools;

import com.example.comptable.agent.tools.dto.DemandeClarificationRequestDTO;
import com.example.comptable.agent.tools.dto.DemandeClarificationResponseDTO;
import com.example.comptable.agent.tools.dto.DoublonVerificationResponseDTO;
import com.example.comptable.agent.tools.dto.HistoriqueFournisseurDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Les 3 outils exposes a l'agent comptable autonome (ai-service, agent_orchestrator.py) une fois
 * qu'il decide d'en appeler un pendant sa boucle de raisonnement - jamais appele par un
 * utilisateur ni directement par Meta. Meme principe d'authentification que
 * {@link com.example.comptable.agent.whatsapp.WhatsAppInternalIngestionController} : cle
 * partagee {@code X-Internal-Api-Key} verifiee contre {@code ai-service.api-key}, exemptee du
 * JWT applicatif dans SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/internal/agent-tools")
public class AgentToolsInternalController {

    private static final Logger log = LoggerFactory.getLogger(AgentToolsInternalController.class);

    private final AgentToolsService agentToolsService;
    private final String cleApiInterne;

    public AgentToolsInternalController(AgentToolsService agentToolsService,
                                         @Value("${ai-service.api-key:}") String cleApiInterne) {
        this.agentToolsService = agentToolsService;
        this.cleApiInterne = cleApiInterne;
    }

    @GetMapping("/doublons")
    public ResponseEntity<DoublonVerificationResponseDTO> verifierDoublon(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @RequestParam UUID entrepriseId,
            @RequestParam BigDecimal montantTtc,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate datePiece) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(agentToolsService.verifierDoublonMontant(entrepriseId, montantTtc, datePiece));
    }

    @GetMapping("/historique-fournisseur")
    public ResponseEntity<HistoriqueFournisseurDTO> consulterHistoriqueFournisseur(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @RequestParam UUID entrepriseId,
            @RequestParam String fournisseur) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(agentToolsService.consulterHistoriqueFournisseur(entrepriseId, fournisseur));
    }

    @PostMapping("/demande-clarification")
    public ResponseEntity<DemandeClarificationResponseDTO> demanderClarification(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @Valid @RequestBody DemandeClarificationRequestDTO requete) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        DemandeClarificationResponseDTO reponse = agentToolsService.demanderClarification(
                requete.entrepriseId(), requete.libelle(), requete.description());
        return ResponseEntity.ok(reponse);
    }

    private boolean acceesRefuse(String cleRecue) {
        boolean refuse = cleApiInterne.isBlank() || !cleApiInterne.equals(cleRecue);
        if (refuse) {
            log.warn("Appel a un outil de l'agent comptable refuse : cle API interne absente ou invalide");
        }
        return refuse;
    }
}
