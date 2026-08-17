package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Solde du en instance d'un tiers, decompose par anciennete (Sprint 10). {@code montant} est
 * toujours signe du point de vue de l'entreprise : positif = le tiers doit de l'argent (creance,
 * typiquement un CLIENT), negatif = l'entreprise doit de l'argent au tiers (dette, typiquement un
 * FOURNISSEUR).
 */
public record BalanceAgeeLigneDto(
        UUID tiersId,
        String tiersNom,
        String typeTiers,
        BigDecimal tranche0a30,
        BigDecimal tranche31a60,
        BigDecimal tranche61a90,
        BigDecimal tranchePlus90,
        BigDecimal total
) {
}
