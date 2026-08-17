package com.example.comptable.copilot.web.dto;

/** Mission P1 (section 15) : chaque reponse factuelle importante du Copilot doit pouvoir etre
 * reliee a une source reelle - meme convention {@code sourceType}/{@code sourceId} que
 * {@code Finding} (agent.finding.domain.Finding), pour rester coherente avec le reste du systeme
 * plutot que d'inventer un nouveau format de reference. */
public record EvidenceRefDto(String sourceType, String sourceId) {
}
