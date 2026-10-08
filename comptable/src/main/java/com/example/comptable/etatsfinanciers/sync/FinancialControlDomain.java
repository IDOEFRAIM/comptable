package com.example.comptable.etatsfinanciers.sync;

/** Périmètre d'une synchronisation des constats financiers : on ne recalcule que les états réellement touchés par l'opération métier. */
public enum FinancialControlDomain {
    COMPTE_RESULTAT,
    BILAN,
    TFT,
    NOTES
}
