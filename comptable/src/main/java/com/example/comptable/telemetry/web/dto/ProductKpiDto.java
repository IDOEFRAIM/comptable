package com.example.comptable.telemetry.web.dto;

/**
 * Mission P1.9 : reponse consolidee aux 4 questions du chantier (section 1). Chaque sous-DTO
 * porte son propre {@code echantillon} (N) - jamais un pourcentage nu (mission section H de
 * docs/P1_9_AUDIT.md : "risque de sur-interpretation" sur un petit echantillon).
 */
public record ProductKpiDto(
        HumanTouchRateDto humanTouchRate,
        MatchingQualityDto matchingBancaire,
        MatchingQualityDto comptabilisation,
        EvidenceResolutionDto resolutionPreuves,
        FindingResolutionTimeDto tempsResolutionFindings,
        CopilotUsageDto usageCopilot,
        WorkQueueDto workQueue
) {
}
