package com.example.comptable.fiscalite.paiement.domain;

public enum StatutPaiement {
    /** Tentative enregistree cote nous, requete pas encore envoyee/traitee par le fournisseur. */
    INITIE,
    /** Requete acceptee par le fournisseur, confirmation du payeur (PIN/USSD, portail) en attente. */
    EN_ATTENTE_CONFIRMATION,
    REUSSI,
    ECHEC
}
