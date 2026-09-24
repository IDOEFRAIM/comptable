package com.example.comptable.stock.inventaire.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code coutUnitaireManuel} n'est requis que pour un article en mode INTERMITTENT sans aucun
 * inventaire anterieur (aucune reference de cout n'existe encore) - jamais devine a zero. */
public record SaisirLigneInventaireRequest(
        @NotNull UUID articleId,
        @NotNull BigDecimal stockCompte,
        BigDecimal coutUnitaireManuel
) {
}
