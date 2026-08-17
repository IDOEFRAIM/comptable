package com.example.comptable.agent.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Miroir exact de ai-service/app/schemas/matching.py::MatchingSource (memes noms de champs,
 * deja camelCase cote Java - aucune conversion necessaire). */
public record MatchingSourceDTO(
        String type,
        String id,
        LocalDate date,
        BigDecimal amount,
        String currency,
        String label,
        String reference,
        String counterparty
) {
}
