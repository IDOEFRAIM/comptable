package com.example.comptable.identite.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(@NotBlank String motDePasseActuel, @NotBlank String nouveauMotDePasse) {
}
