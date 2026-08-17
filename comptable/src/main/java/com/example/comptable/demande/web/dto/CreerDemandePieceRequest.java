package com.example.comptable.demande.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record CreerDemandePieceRequest(
        @NotBlank String libelle,
        String description,
        LocalDate dateLimite
) {
}
