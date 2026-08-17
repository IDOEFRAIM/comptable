package com.example.comptable.agent.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Miroir exact de ai-service/app/schemas/matching.py::MatchingCandidate. */
public record MatchingCandidateDTO(
        String type,
        String id,
        LocalDate date,
        BigDecimal amount,
        String currency,
        String label,
        String reference,
        String counterparty,
        String status
) {
}
