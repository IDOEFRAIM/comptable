package com.example.comptable.identite.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterCabinetRequest(
        @NotBlank String raisonSocialeCabinet,
        @NotBlank @Size(min = 2, max = 2) String pays,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8) String motDePasse,
        @NotBlank String nom,
        @NotBlank String prenom,
        String telephone
) {
}
