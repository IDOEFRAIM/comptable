package com.example.comptable.comptabilite.regularisation.domain;

/**
 * Regularisations de fin de periode (mission cloture Etape 4). PRODUIT_A_REPORTER partage
 * exactement la meme mecanique que PRODUIT_CONSTATE_AVANCE (voir RegularisationRule) : aucune
 * difference de traitement n'est verifiee dans ce perimetre entre les deux, seule la
 * classification differe - une distinction inventee aurait ete pire qu'aucune distinction.
 */
public enum RegularisationType {
    /** Charge constatee d'avance (CCA) : une charge deja comptabilisee couvre en partie l'exercice
     * suivant. A la cloture, debit compte de contrepartie (bilan) / credit compte d'origine (charge)
     * pour la part future. */
    CHARGE_CONSTATEE_AVANCE,
    /** Produit constate d'avance (PCA) : un produit deja comptabilise couvre en partie l'exercice
     * suivant. Debit compte d'origine (produit) / credit compte de contrepartie (bilan) pour la
     * part future. */
    PRODUIT_CONSTATE_AVANCE,
    /** Facture non parvenue (FNP) : une charge de l'exercice n'a pas encore de facture fournisseur.
     * Debit compte de charge / credit compte d'attente tiers, pour le montant estime entier. */
    FACTURE_NON_PARVENUE,
    /** Produit a recevoir (PAR) : un produit de l'exercice n'a pas encore ete facture au client.
     * Debit compte d'attente tiers / credit compte de produit, pour le montant estime entier. */
    PRODUIT_A_RECEVOIR,
    /** Charge a payer (CAP) : meme mecanique que FACTURE_NON_PARVENUE, pour une charge hors achat
     * (ex: interets courus, salaires courus). */
    CHARGE_A_PAYER,
    /** Produit a reporter : meme mecanique que PRODUIT_CONSTATE_AVANCE (voir note de classe). */
    PRODUIT_A_REPORTER
}
