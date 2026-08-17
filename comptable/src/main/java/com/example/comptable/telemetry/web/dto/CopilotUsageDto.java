package com.example.comptable.telemetry.web.dto;

/** Mission P1.9 (section 13/14) : usage et qualite du Copilot sur les 30 derniers jours (fenetre
 * glissante, voir ProductKpiService). */
public record CopilotUsageDto(
        int questions,
        int reponsesAvecPreuve,
        int fallback,
        Double latenceMoyenneMs,
        int feedbackPositif,
        int feedbackNegatif,
        int sansFeedback,
        Double tauxFallbackPourcent,
        Double tauxFeedbackPositifPourcent,
        Double tauxFeedbackNegatifPourcent,
        Double tauxReponseAvecPreuvePourcent
) {
}
