package com.example.comptable.comptabilite.domain;

/** Strategie d'initialisation du PCE d'une entreprise depuis le referentiel SYSCOHADA. */
public enum ModeImportReference {
    /** Tous les comptes actifs du referentiel. */
    TOUT,
    /** Seulement les codes explicitement listes (+ leurs ancetres, importes automatiquement). */
    SELECTION
}
