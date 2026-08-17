package com.example.comptable.copilot.web.dto;

import java.util.List;
import java.util.UUID;

/** Mission P1 (section 18, 47) : contrat de reponse structure du Copilot - le texte genere n'est
 * qu'une presentation des faits structures deja calcules en Java, jamais l'inverse.
 * {@code queryId} (mission P1.9, section 30) : identifie la ligne de telemetrie associee pour
 * permettre un feedback 👍/👎 ulterieur - null si l'enregistrement de la telemetrie a echoue
 * (jamais bloquant pour la reponse elle-meme, voir CopilotTelemetryRecorder). */
public record CopilotResponseDto(
        String answer,
        double confidence,
        List<EvidenceRefDto> evidence,
        List<String> recommendedActions,
        UUID queryId
) {
}
