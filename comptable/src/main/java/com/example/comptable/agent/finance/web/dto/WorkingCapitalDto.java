package com.example.comptable.agent.finance.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Capability get_working_capital (STEP 8, section 12 du plan) : working_capital = receivables +
 * inventory - payables. {@code inventory} provient de {@code Article.valeurStockTotale} (deja
 * maintenu par le module stock, CUMP - {@code CumpValuationService}), jamais recalcule ici.
 * {@code status}="NOT_AVAILABLE" avec {@code missingComponents} rempli si jamais un composant ne
 * peut pas etre calcule de maniere fiable - jamais un chiffre invente pour combler l'absence. */
public record WorkingCapitalDto(
        LocalDate asOf,
        String status,
        BigDecimal workingCapital,
        BigDecimal receivables,
        BigDecimal payables,
        BigDecimal inventory,
        List<String> missingComponents
) {
    public static final String STATUT_DISPONIBLE = "AVAILABLE";
    public static final String STATUT_NON_DISPONIBLE = "NOT_AVAILABLE";
}
