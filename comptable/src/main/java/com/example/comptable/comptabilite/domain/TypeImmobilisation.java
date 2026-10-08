package com.example.comptable.comptabilite.domain;

/** Nature d'une immobilisation : determine le purpose comptable (IMMOBILISATION_CORPORELLE ou
 * IMMOBILISATION_INCORPORELLE) demande au resolveur lors de l'acquisition. Choisie explicitement. */
public enum TypeImmobilisation {
    CORPORELLE,
    INCORPORELLE
}
