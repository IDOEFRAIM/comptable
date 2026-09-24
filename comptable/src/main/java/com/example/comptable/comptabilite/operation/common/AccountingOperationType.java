package com.example.comptable.comptabilite.operation.common;

/** Type d'operation metier traite par l'AccountingOperationEngine. */
public enum AccountingOperationType {
    ACHAT,
    VENTE,
    REGLEMENT_CLIENT,
    REGLEMENT_FOURNISSEUR,
    AVOIR_VENTE,
    AVOIR_ACHAT,
    /** Regularisation d'un ecart d'inventaire physique - la seule operation sans tiers. */
    REGULARISATION_INVENTAIRE,
    /** Regularisation de fin de periode (CCA/PCA/FNP/CAP/PAR) - voir comptabilite.regularisation. */
    REGULARISATION,
    /** Contre-passation (reprise) d'une regularisation deja validee, jamais generee automatiquement. */
    REGULARISATION_REPRISE,
    /** Dotation ou reprise de provision - voir comptabilite.provision. */
    PROVISION,
    /** Constatation d'une perte de valeur - voir comptabilite.depreciation. */
    DEPRECIATION,
    /** Ecriture de determination du resultat / cloture d'exercice, via ClotureRule. */
    CLOTURE_EXERCICE
}
