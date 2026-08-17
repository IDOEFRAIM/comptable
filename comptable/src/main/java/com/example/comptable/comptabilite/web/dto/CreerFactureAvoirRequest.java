package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** {@code total} reprend integralement toutes les lignes de la facture d'origine ; sinon
 * {@code lignes} precise, pour un avoir partiel, quelles lignes et quelles quantites crediter. */
public record CreerFactureAvoirRequest(
        String motif,
        boolean retourStock,
        @NotNull Boolean total,
        List<@Valid LigneAvoirRequest> lignes
) {
}
