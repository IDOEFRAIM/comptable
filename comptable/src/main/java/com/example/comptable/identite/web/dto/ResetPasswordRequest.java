package com.example.comptable.identite.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(@NotBlank String jeton, @NotBlank String nouveauMotDePasse) {
}
