package com.example.comptable.document.domain;

public enum TypeDocument {
    FACTURE_ACHAT,
    FACTURE_VENTE,
    RECU,
    RELEVE_BANCAIRE,
    DOCUMENT_PAIE,
    DOCUMENT_SOCIAL,
    DOCUMENT_FISCAL,
    CONTRAT,
    AUTRE;

    /** Les factures sont les seules pieces porteuses d'un numero fournisseur, d'un tiers et de montants a rapprocher. */
    public boolean estFacture() {
        return this == FACTURE_ACHAT || this == FACTURE_VENTE;
    }
}
