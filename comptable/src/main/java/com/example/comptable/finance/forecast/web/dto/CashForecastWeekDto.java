package com.example.comptable.finance.forecast.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Une semaine du forecast (mission P2, section 5 du plan). Invariants garantis par
 * {@code CashForecastService} (jamais recalcules cote Python, voir CashForecastServiceTest) :
 * <pre>
 *   closingCash == openingCash + netCashFlow
 *   openingCash(semaine N+1) == closingCash(semaine N)
 *   inflows == knownInflows + expectedInflows + predictedInflows
 *   outflows == knownOutflows + expectedOutflows + predictedOutflows
 * </pre>
 */
public record CashForecastWeekDto(
        int weekIndex,
        LocalDate weekStart,
        LocalDate weekEnd,
        BigDecimal openingCash,
        BigDecimal inflows,
        BigDecimal outflows,
        BigDecimal netCashFlow,
        BigDecimal closingCash,
        BigDecimal knownInflows,
        BigDecimal expectedInflows,
        BigDecimal predictedInflows,
        BigDecimal knownOutflows,
        BigDecimal expectedOutflows,
        BigDecimal predictedOutflows
) {
}
