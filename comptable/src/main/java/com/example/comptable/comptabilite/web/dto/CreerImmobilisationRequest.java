package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Enregistrement d'une immobilisation (statut ACQUISE : aucun amortissement avant la mise en service). */
public record CreerImmobilisationRequest(
        @NotNull UUID compteId,
        @NotNull UUID compteAmortissementId,
        @NotBlank String libelle,
        @NotNull TypeImmobilisation type,
        @NotNull LocalDate dateAcquisition,
        @NotNull @DecimalMin(value = "0.01") BigDecimal valeurOrigine,
        @NotNull @Min(1) Integer dureeAnnees,
        @NotNull ModeAmortissement mode
) {
}
