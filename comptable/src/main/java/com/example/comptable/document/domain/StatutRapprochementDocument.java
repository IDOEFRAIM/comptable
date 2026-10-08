package com.example.comptable.document.domain;

/** Etat du rapprochement d'une piece avec la facture/operation a laquelle elle appartient. */
public enum StatutRapprochementDocument {
    /** Aucune association (encore) : normal pour une piece a comptabiliser. */
    NON_RAPPROCHE,
    /** Associee a une facture (certain automatique, ou association confirmee par un humain). */
    RAPPROCHE,
    /** Une seule candidate probable : proposee, en attente de confirmation. */
    A_CONFIRMER,
    /** Plusieurs candidates plausibles : jamais tranche par le systeme. */
    AMBIGU
}
