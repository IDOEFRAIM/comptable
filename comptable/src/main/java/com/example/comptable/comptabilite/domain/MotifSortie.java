package com.example.comptable.comptabilite.domain;

/**
 * Motif de sortie d'une immobilisation du patrimoine. Seul le motif est enregistre ici : les
 * ecritures de sortie (valeur nette comptable, plus ou moins-value, reprise d'amortissements) ne sont
 * pas generees tant que leurs regles ne sont pas verifiees - elles se saisissent a la main.
 */
public enum MotifSortie {
    CESSION,
    MISE_AU_REBUT
}
