package com.example.comptable.etatsfinanciers.definition;

/** Etat financier du Systeme normal. Le Systeme minimal de tresorerie est hors perimetre (voir {@link PresentationSystem}). */
public enum StatementType {
    BILAN_ACTIF, BILAN_PASSIF, COMPTE_RESULTAT, TFT, NOTES_R4,
    /** Dossier complet fige (Bilan, CR, TFT, Notes) : uniquement dans un snapshot, sans definition propre. */
    DOSSIER_COMPLET
}
