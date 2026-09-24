package com.example.comptable.finance.forecast.web.dto;

import com.example.comptable.finance.forecast.domain.ForecastConfidence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Racine du "13-Week Cash Forecast" (mission P2, section 5 du plan) - le contrat structure que
 * Python/MCP/LangGraph consomment sans jamais recalculer quoi que ce soit (section 6 :
 * "Python ne doit pas relire la base, recalculer les historiques, calculer le cash closing").
 */
public record CashForecastDto(
        LocalDate asOf,
        LocalDate periodStart,
        LocalDate periodEnd,
        String currency,
        BigDecimal openingCash,
        BigDecimal closingCash,
        BigDecimal minimumCashThreshold,
        ForecastConfidence confidence,
        List<String> dataQualityWarnings,
        List<String> limitations,
        List<CashForecastWeekDto> weeks,
        List<LiquidityEventDto> liquidityEvents
) {
}
