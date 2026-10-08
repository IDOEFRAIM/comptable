package com.example.comptable.document.domain;

/** Relation d'une piece recue avec les demandes de pieces ouvertes de l'entreprise. */
public enum StatutDemandeDocument {
    /** Aucune demande ouverte ne correspond (ou la piece n'est pas concernee). */
    AUCUNE,
    /** Une seule demande plausible mais pas certaine : proposee, a confirmer par le comptable. */
    A_CONFIRMER,
    /** Plusieurs demandes egalement plausibles : jamais tranche par le systeme. */
    AMBIGU,
    /** Rattachee a la demande (certitude ou confirmation humaine). */
    LIEE
}
