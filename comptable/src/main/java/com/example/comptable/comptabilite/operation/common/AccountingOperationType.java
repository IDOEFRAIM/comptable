package com.example.comptable.comptabilite.operation.common;

/** Type d'operation metier traite par l'AccountingOperationEngine. */
public enum AccountingOperationType {
    ACHAT,
    VENTE,
    REGLEMENT_CLIENT,
    REGLEMENT_FOURNISSEUR,
    AVOIR_VENTE,
    AVOIR_ACHAT,
    /** Entree en stock (inventaire permanent) : contrepartie comptable calculee par le module Stock (CUMP). */
    STOCK_ENTREE,
    /** Sortie de stock (vente, consommation, production) a la valeur CUMP calculee par le module Stock. */
    STOCK_SORTIE,
    /** Ajustement manuel de stock (surplus ou perte). */
    STOCK_AJUSTEMENT,
    /** Acquisition d'une immobilisation (actif, TVA recuperable, fournisseur) - voir operation.immobilisation. */
    IMMOBILISATION_ACQUISITION,
    /** Dotation annuelle aux amortissements d'UNE immobilisation (montant calcule par le domaine Immobilisation). */
    AMORTISSEMENT_DOTATION,
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
    CLOTURE_EXERCICE,
    /** Contrepassation de l'ecriture de validation d'une facture annulee (memes comptes et tiers, sens inverse) ; source FACTURE/&lt;id&gt;/ANNULATION. */
    ANNULATION_FACTURE
}
