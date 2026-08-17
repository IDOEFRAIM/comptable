package com.example.comptable.agent.whatsapp.dto;

import com.example.comptable.agent.fraude.AlerteFraude;
import com.example.comptable.agent.fraude.NiveauRisqueFraude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Miroir JSON (camelCase, cf. ai-service/app/schemas/fraude.py::AlerteFraude) d'une alerte de
 * fraude issue de l'analyse visuelle deja effectuee par le microservice IA. Volontairement une
 * DTO distincte de {@link AlerteFraude} : ce dernier reste le type interne utilise par
 * {@link com.example.comptable.agent.fraude.AnalyseFraudeService}, celui-ci n'est qu'un contrat
 * d'echange sur le fil, converti explicitement (cf. DocumentService.versAlertesFraude).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlerteFraudeDTO(
        @NotBlank String code,
        @NotBlank String motif,
        @NotNull NiveauRisqueFraude gravite,
        @NotBlank String actionRecommandee
) {
    public AlerteFraude versAlerteFraude() {
        return new AlerteFraude(code, motif, gravite, actionRecommandee);
    }
}
