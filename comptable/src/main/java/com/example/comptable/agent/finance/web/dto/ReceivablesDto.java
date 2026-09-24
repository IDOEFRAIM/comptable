package com.example.comptable.agent.finance.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Capability get_receivables (STEP 8, section 10 du plan) : reshape de
 * {@code BalanceAgeeDto} (deja existant, {@code BalanceAgeeService}) filtre sur les tiers CLIENT -
 * aucun second calcul d'anciennete, uniquement une projection. {@code current} = tranche 0-30
 * jours, {@code overdue} = somme des tranches 31-60/61-90/plus-90 (meme convention que
 * {@code CollectionRiskService}). */
public record ReceivablesDto(
        LocalDate asOf,
        BigDecimal total,
        BigDecimal current,
        BigDecimal overdue,
        BigDecimal aging0a30,
        BigDecimal aging31a60,
        BigDecimal aging61a90,
        BigDecimal agingPlus90,
        List<TopTiersDto> topDebtors
) {
}
