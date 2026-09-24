package com.example.comptable.agent.finance.web;

import com.example.comptable.agent.finance.AiFinanceQueryService;
import com.example.comptable.agent.finance.web.dto.AiFinancialSnapshotDto;
import com.example.comptable.agent.finance.web.dto.PayablesDto;
import com.example.comptable.agent.finance.web.dto.ReceivablesDto;
import com.example.comptable.agent.finance.web.dto.WorkingCapitalDto;
import com.example.comptable.finance.forecast.web.dto.CashForecastDto;
import com.example.comptable.finance.web.dto.CashPositionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Frontiere Python (ai-service, Financial Intelligence Layer - STEP 8 du plan d'implementation)
 * -&gt; Java pour les capabilities financieres. Meme mecanisme d'authentification que
 * {@code /api/v1/internal/ai-accounting/**} (STEP 4-7) : cle partagee X-Internal-Api-Key,
 * exemptee du JWT applicatif. Ne fait qu'orchestrer l'appel a {@link AiFinanceQueryService}, qui
 * delegue integralement aux services financiers deja existants - aucun calcul ici.
 */
@RestController
@RequestMapping("/api/v1/internal/ai-finance")
public class AiFinanceController {

    private static final Logger log = LoggerFactory.getLogger(AiFinanceController.class);

    private final AiFinanceQueryService aiFinanceQueryService;
    private final String cleApiInterne;

    public AiFinanceController(AiFinanceQueryService aiFinanceQueryService,
                                @Value("${ai-service.api-key:}") String cleApiInterne) {
        this.aiFinanceQueryService = aiFinanceQueryService;
        this.cleApiInterne = cleApiInterne;
    }

    @GetMapping("/{entrepriseId}/cash")
    public ResponseEntity<CashPositionDto> cash(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @PathVariable UUID entrepriseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(aiFinanceQueryService.obtenirPositionTresorerie(entrepriseId, date));
    }

    @GetMapping("/{entrepriseId}/snapshot")
    public ResponseEntity<AiFinancialSnapshotDto> snapshot(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @PathVariable UUID entrepriseId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(aiFinanceQueryService.obtenirSnapshot(entrepriseId, periodStart, periodEnd));
    }

    @GetMapping("/{entrepriseId}/receivables")
    public ResponseEntity<ReceivablesDto> receivables(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @PathVariable UUID entrepriseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(aiFinanceQueryService.obtenirCreances(entrepriseId, date));
    }

    @GetMapping("/{entrepriseId}/payables")
    public ResponseEntity<PayablesDto> payables(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @PathVariable UUID entrepriseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(aiFinanceQueryService.obtenirDettes(entrepriseId, date));
    }

    @GetMapping("/{entrepriseId}/working-capital")
    public ResponseEntity<WorkingCapitalDto> workingCapital(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @PathVariable UUID entrepriseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(aiFinanceQueryService.obtenirFondsDeRoulement(entrepriseId, date));
    }

    @GetMapping("/{entrepriseId}/cash-forecast")
    public ResponseEntity<CashForecastDto> cashForecast(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @PathVariable UUID entrepriseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) Integer weeks,
            @RequestParam(required = false) BigDecimal minimumCashThreshold) {
        if (acceesRefuse(cleRecue)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(aiFinanceQueryService.obtenirPrevisionTresorerie(entrepriseId, startDate, weeks, minimumCashThreshold));
    }

    private boolean acceesRefuse(String cleRecue) {
        boolean refuse = cleApiInterne.isBlank() || !cleApiInterne.equals(cleRecue);
        if (refuse) {
            log.warn("Appel aux capabilities AI Finance refuse : cle API interne absente ou invalide");
        }
        return refuse;
    }
}
