package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.ModeGestionStock;
import com.example.comptable.stock.domain.PolitiqueStockNegatif;
import com.example.comptable.stock.domain.TypeArticle;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code politiqueStockNegatif}/{@code modeGestionStock} nullable : appliquent leur defaut
 * (INTERDIT/PERMANENT, comportement historique du moteur) si non fournis. */
public record CreerArticleRequest(
        @NotBlank String codeArticle,
        @NotBlank String designation,
        String categorie,
        @NotNull TypeArticle type,
        @NotBlank String uniteMesure,
        @NotNull @DecimalMin(value = "0.00") BigDecimal prixVenteHt,
        @NotNull @DecimalMin(value = "0.00") BigDecimal tauxTva,
        @NotNull UUID compteStockId,
        @NotNull UUID compteAchatId,
        @NotNull UUID compteVenteId,
        @NotNull UUID compteVariationId,
        BigDecimal stockMinimum,
        PolitiqueStockNegatif politiqueStockNegatif,
        ModeGestionStock modeGestionStock
) {
}
