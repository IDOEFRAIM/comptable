package com.example.comptable.etatsfinanciers.definition;

/** Statut de verification par rapport a la source primaire (jamais confondu avec {@link ProvenanceType}). Seul {@code VERIFIED} peut etre code. */
public enum VerificationStatus {
    VERIFIED, PARTIAL, UNVERIFIED
}
