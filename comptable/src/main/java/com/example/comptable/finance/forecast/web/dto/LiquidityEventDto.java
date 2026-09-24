package com.example.comptable.finance.forecast.web.dto;

import com.example.comptable.finance.forecast.domain.LiquidityEventType;
import com.example.comptable.finance.forecast.domain.LiquiditySeverity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Un evenement de liquidite detecte sur la serie hebdomadaire (mission P2, section 20 du plan) -
 * toujours calcule deterministiquement, jamais par le LLM (voir CashRiskPolicy cote Python qui ne
 * fait que LIRE ces evenements, jamais les recalculer). */
public record LiquidityEventDto(
        LiquidityEventType type,
        LiquiditySeverity severity,
        int weekIndex,
        LocalDate weekStart,
        BigDecimal amount,
        String reason,
        String source
) {
}
