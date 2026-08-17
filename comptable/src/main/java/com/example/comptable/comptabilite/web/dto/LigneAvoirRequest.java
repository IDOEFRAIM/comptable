package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** Selectionne une ligne de la facture d'origine et la quantite a crediter (avoir partiel) ;
 * ignore pour un avoir total, ou toutes les lignes sont reprises integralement. */
public record LigneAvoirRequest(
        @NotNull UUID ligneFactureId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal quantite
) {
}
