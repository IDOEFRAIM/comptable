package com.example.comptable.copilot.web.dto;

/** Mission P1.9 (section 30/31) : feedback leger sur une reponse du Copilot -
 * {@code feedback} = "POSITIVE"/"NEGATIVE", {@code reason} optionnel
 * (incorrect/incomplet/inutile/trop_vague pour un retour negatif). */
public record CopilotFeedbackRequest(String feedback, String reason) {
}
