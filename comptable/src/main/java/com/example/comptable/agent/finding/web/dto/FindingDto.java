package com.example.comptable.agent.finding.web.dto;

import com.example.comptable.agent.finding.domain.Finding;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record FindingDto(
        UUID id,
        String type,
        String severity,
        String sourceType,
        String sourceId,
        String title,
        String description,
        Double confidence,
        Map<String, Object> evidence,
        String recommendedAction,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        UUID assignedToId,
        String assignedToNom,
        LocalDate dueDate,
        String controlCode,
        String category,
        String controlSeverity,
        String resolutionType,
        String resolutionComment,
        OffsetDateTime lastDetectedAt
) {
    public static FindingDto from(Finding f) {
        return new FindingDto(
                f.getId(),
                f.getType().name(),
                f.getSeverity().name(),
                f.getSourceType(),
                f.getSourceId(),
                f.getTitle(),
                f.getDescription(),
                f.getConfidence(),
                f.getEvidenceJson(),
                f.getRecommendedAction(),
                f.getStatus().name(),
                f.getCreatedAt(),
                f.getResolvedAt(),
                f.getAssignedTo() != null ? f.getAssignedTo().getId() : null,
                f.getAssignedTo() != null ? f.getAssignedTo().getNom() + " " + f.getAssignedTo().getPrenom() : null,
                f.getDueDate(),
                f.getControlCode(),
                f.getCategory().name(),
                f.getControlSeverity().name(),
                f.getResolutionType() != null ? f.getResolutionType().name() : null,
                f.getResolutionComment(),
                f.getLastDetectedAt()
        );
    }
}
