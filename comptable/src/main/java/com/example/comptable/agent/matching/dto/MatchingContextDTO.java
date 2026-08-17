package com.example.comptable.agent.matching.dto;

import java.util.List;

/** Miroir exact de ai-service/app/schemas/matching.py::MatchingContext. */
public record MatchingContextDTO(
        String country,
        String accountingFramework,
        String companyId,
        List<HistoricalPaymentDTO> history
) {
    public MatchingContextDTO {
        history = history != null ? history : List.of();
    }
}
