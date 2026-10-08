package com.example.comptable.fiscalite.core;

/**
 * Traitement fiscal d'une opération, indépendant du compte comptable (aucun « compte 443 = TVA collectée » universel). Les valeurs sont génériques : leur sens exact (taux, déductibilité) vient du cadre
 * fiscal vérifié, jamais du code.
 */
public enum TaxTreatment {
    OUTPUT_TAX,
    INPUT_TAX,
    EXEMPT,
    OUT_OF_SCOPE,
    NON_DEDUCTIBLE,
    PARTIALLY_DEDUCTIBLE,
    /** Non déterminable avec certitude : jamais calculé silencieusement, toujours accompagné d'un constat. */
    TO_REVIEW
}
