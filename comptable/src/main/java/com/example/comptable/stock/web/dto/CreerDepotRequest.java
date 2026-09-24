package com.example.comptable.stock.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreerDepotRequest(@NotBlank String code, @NotBlank String libelle) {
}
