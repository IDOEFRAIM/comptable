package com.example.comptable.agent.matching.dto;

import java.util.List;

/** Miroir exact de ai-service/app/schemas/matching.py::MatchingRequest. */
public record MatchingRequestDTO(
        MatchingSourceDTO source,
        List<MatchingCandidateDTO> candidates,
        MatchingContextDTO context
) {
}
