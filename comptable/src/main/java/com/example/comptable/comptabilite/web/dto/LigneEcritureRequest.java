package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record LigneEcritureRequest(
        @NotNull UUID compteId,
        String libelle,
        @NotNull BigDecimal debit,
        @NotNull BigDecimal credit,
        UUID tiersId
) {
}
