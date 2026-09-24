package com.example.comptable.comptabilite.operation.common;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Reduction appliquee a une operation. {@code montant} (valeur absolue) et {@code pourcentage}
 * sont mutuellement exclusifs (le moteur privilegie {@code montant} si les deux sont fournis).
 * {@code condition} est une note libre (ex. "paiement sous 8 jours" pour un escompte) - jamais
 * interpretee automatiquement.
 */
public record ReductionRequest(
        @NotNull TypeReduction typeReduction,
        BigDecimal montant,
        BigDecimal pourcentage,
        String condition
) {
}
