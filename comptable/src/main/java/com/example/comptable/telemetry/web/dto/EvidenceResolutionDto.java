package com.example.comptable.telemetry.web.dto;

/** Mission P1.9 (section 10) : demandes de pieces (Missing Evidence). */
public record EvidenceResolutionDto(
        int creees,
        int completees,
        int annulees,
        int enAttente,
        Double tauxResolutionPourcent,
        Double dureeMoyenneResolutionHeures
) {
}
