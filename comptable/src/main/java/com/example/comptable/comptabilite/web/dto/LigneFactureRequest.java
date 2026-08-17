package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code articleId} est optionnel : renseigne uniquement quand la ligne vend un article de
 * stock suivi en inventaire permanent (declenche le destockage automatique a la validation). */
public record LigneFactureRequest(
        @NotBlank String designation,
        @NotNull @DecimalMin(value = "0.01") BigDecimal quantite,
        @NotNull @DecimalMin(value = "0.00") BigDecimal prixUnitaire,
        @NotNull @DecimalMin(value = "0.00") BigDecimal tauxTva,
        UUID articleId
) {
}
