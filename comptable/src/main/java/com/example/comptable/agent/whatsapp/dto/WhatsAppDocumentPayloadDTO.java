package com.example.comptable.agent.whatsapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Payload unifie envoye par le microservice IA (ai-service, FastAPI) a
 * {@code POST /api/v1/internal/whatsapp/documents} une fois qu'une piece recue par WhatsApp a
 * ete telechargee, extraite (VLM) et analysee visuellement pour la fraude. Miroir JSON exact de
 * ai-service/app/schemas/ingestion.py::WhatsAppDocumentPayload (memes alias camelCase).
 * <p>
 * Envoye comme partie multipart {@code metadata} (Content-Type: application/json), a cote de la
 * partie {@code fichier} portant le binaire original - voir
 * {@link com.example.comptable.agent.whatsapp.WhatsAppInternalIngestionController}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WhatsAppDocumentPayloadDTO(
        @NotBlank String clientPhone,
        @NotBlank String messageId,
        @NotNull @Valid DonneesExtraitesDTO donnees,
        @Valid List<AlerteFraudeDTO> alertesFraude
) {
    /** {@code alertesFraude} est optionnel cote Python (liste vide par defaut) : normalise ici
     * en liste vide plutot que null, pour que les appelants n'aient jamais a verifier la nullite. */
    public WhatsAppDocumentPayloadDTO {
        alertesFraude = alertesFraude != null ? alertesFraude : List.of();
    }
}
