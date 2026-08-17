package com.example.comptable.agent.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Miroir exact de ai-service/app/schemas/matching.py::MatchingResponse. {@code decision} et
 * {@code matchType} restent des chaines brutes ici (pas d'enum Jackson strict) : c'est
 * {@code MatchDecisionEngine} qui les interprete prudemment (valueOf avec repli explicite), pour
 * ne jamais faire echouer la desimplification si ai-service introduit une nouvelle valeur avant
 * que ce module Java ne soit mis a jour. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchingResponseDTO(
        String decision,
        double confidence,
        String matchType,
        List<MatchProposalDTO> matches,
        List<String> reasons,
        List<String> warnings,
        boolean requiresHumanReview
) {
    public MatchingResponseDTO {
        matches = matches != null ? matches : List.of();
        reasons = reasons != null ? reasons : List.of();
        warnings = warnings != null ? warnings : List.of();
    }
}
