package com.example.comptable.stock.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreerArticleRequest(
        @NotBlank String codeArticle,
        @NotBlank String designation,
        String categorie,
        @NotBlank String uniteMesure,
        @NotNull @DecimalMin(value = "0.00") BigDecimal prixVenteHt,
        @NotNull @DecimalMin(value = "0.00") BigDecimal tauxTva,
        @NotNull UUID compteStockId,
        @NotNull UUID compteAchatId,
        @NotNull UUID compteVenteId,
        @NotNull UUID compteVariationId
) {
}
