package com.example.comptable.fiscalite.web.dto;

import com.example.comptable.fiscalite.domain.TypeImpot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreerDeclarationRequest(
        @NotNull TypeImpot typeImpot,
        @NotBlank String periode,
        @NotNull LocalDate dateEcheance,
        BigDecimal montant
) {
}
