package com.example.comptable.entreprise.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InviterUtilisateurRequest(
        @NotBlank @Email String email,
        @NotBlank String nom,
        @NotBlank String prenom,
        String telephone,
        @NotNull RoleInvitable role
) {
}
