package com.example.comptable.agent.tools.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Requete de l'outil {@code demander_clarification_client}. */
public record DemandeClarificationRequestDTO(
        @NotNull UUID entrepriseId,
        @NotBlank String libelle,
        String description
) {
}
