package com.example.comptable.etatsfinanciers.definition;

/** Origine d'une donnee reglementaire. {@code DERIVED_FROM_PRIMARY} n'est jamais promu en {@code DIRECT_PRIMARY}. */
public enum ProvenanceType {
    DIRECT_PRIMARY, DERIVED_FROM_PRIMARY, CORROBORATED_ONLY
}
