package com.example.comptable.agent.matching.web.dto;

import com.example.comptable.agent.matching.domain.AiMatchProposal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AiMatchProposalDto(
        UUID id,
        String sourceType,
        String sourceId,
        String candidateType,
        String candidateId,
        double confidence,
        String decision,
        String matchType,
        List<String> reasons,
        List<String> warnings,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime validatedAt
) {
    public static AiMatchProposalDto from(AiMatchProposal p) {
        return new AiMatchProposalDto(
                p.getId(),
                p.getSourceType() != null ? p.getSourceType().name() : null,
                p.getSourceId(),
                p.getCandidateType() != null ? p.getCandidateType().name() : null,
                p.getCandidateId(),
                p.getConfidence(),
                p.getDecision() != null ? p.getDecision().name() : null,
                p.getMatchType(),
                p.getReasonsJson(),
                p.getWarningsJson(),
                p.getStatus().name(),
                p.getCreatedAt(),
                p.getValidatedAt()
        );
    }
}
