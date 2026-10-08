package com.example.comptable.fiscalite.core;

/** Cycle d'une période déclarative : OPEN → PREPARING → READY_FOR_REVIEW → REVIEWED → FINAL (immuable). Toute modification après READY_FOR_REVIEW ramène à PREPARING et annule la revue. */
public enum TaxPeriodStatus {
    OPEN,
    PREPARING,
    READY_FOR_REVIEW,
    REVIEWED,
    FINAL
}
