package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.ModeAmortissement;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreerImmobilisationRequest(
        @NotNull UUID compteId,
        @NotNull UUID compteAmortissementId,
        @NotBlank String libelle,
        @NotNull LocalDate dateAcquisition,
        @NotNull @DecimalMin(value = "0.01") BigDecimal valeurOrigine,
        @NotNull @Min(1) Integer dureeAnnees,
        @NotNull ModeAmortissement mode
) {
}
