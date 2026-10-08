package com.example.comptable.document.domain;

/** Qualite de l'identification du tiers d'une piece. Seul {@code EXACT} permet de progresser sans intervention. */
public enum QualiteClassification {
    EXACT,
    PROBABLE,
    AMBIGU,
    INCONNU
}
