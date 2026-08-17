package com.example.comptable.agent.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Miroir exact de ai-service/app/schemas/matching.py::MatchProposal. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchProposalDTO(String candidateId, String candidateType, double score) {
}
