package com.example.comptable.comptabilite.allocation;

/** Qui a decide l'allocation : le comptable, le systeme sur un cas certain, ou un rapprochement bancaire confirme. */
public enum SourceAllocation {
    USER,
    SYSTEM_EXACT,
    BANK_RECONCILIATION
}
