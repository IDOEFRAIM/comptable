package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.MotifSortie;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Sortie du patrimoine. {@code montantCession} : CESSION uniquement, facultatif. Aucune ecriture n'est generee. */
public record SortirImmobilisationRequest(
        @NotNull LocalDate dateSortie,
        @NotNull MotifSortie motif,
        @DecimalMin(value = "0.00") BigDecimal montantCession
) {
}
