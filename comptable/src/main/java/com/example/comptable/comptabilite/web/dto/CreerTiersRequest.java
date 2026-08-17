package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.TypeTiers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreerTiersRequest(
        @NotNull TypeTiers type,
        @NotBlank String nom,
        String numeroContribuable
) {
}
