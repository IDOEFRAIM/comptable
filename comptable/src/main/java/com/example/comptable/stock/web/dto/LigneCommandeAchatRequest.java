package com.example.comptable.stock.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record LigneCommandeAchatRequest(
        @NotNull UUID articleId,
        @NotNull @DecimalMin(value = "0.001") BigDecimal quantite,
        @NotNull @DecimalMin(value = "0.00") BigDecimal prixUnitaireHt
) {
}
