package com.example.comptable.comptabilite.allocation;

/** Une allocation {@code REVERSED} ne compte plus dans aucun calcul (elle reste dans l'historique). */
public enum StatutAllocation {
    ACTIVE,
    REVERSED
}
