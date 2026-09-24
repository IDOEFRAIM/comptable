package com.example.comptable.finance.forecast.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Charge recurrente detectee sur l'historique (mission P2, section 13 du plan - loyers, telecom,
 * abonnements...) - detection deterministe (moyenne + stabilite des montants sur des occurrences
 * mensuelles), jamais par le LLM (section 13 : "ne pas utiliser le LLM pour cette detection").
 * Cadence mensuelle uniquement dans cette version (limite assumee et documentee,
 * RecurringFlowDetector) - hebdomadaire/trimestrielle non detectees.
 */
public record RecurringFlowDto(
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        BigDecimal averageAmount,
        int occurrences,
        LocalDate lastOccurrenceDate,
        LocalDate nextExpectedDate
) {
}
