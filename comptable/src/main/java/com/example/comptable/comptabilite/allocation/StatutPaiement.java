package com.example.comptable.comptabilite.allocation;

/** Statut de paiement d'une facture, CALCULE a partir des allocations actives (jamais un drapeau saisi). */
public enum StatutPaiement {
    NON_PAYEE,
    PARTIELLEMENT_PAYEE,
    PAYEE
}
