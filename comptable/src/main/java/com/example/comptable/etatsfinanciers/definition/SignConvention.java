package com.example.comptable.etatsfinanciers.definition;

/** Convention de signe du montant d'une ligne. */
public enum SignConvention {
    /** Contribution algebrique au resultat : credit - debit (produits positifs, charges negatives). */
    RESULT_ALGEBRAIC,
    /** Actif : debit - credit. */
    ASSET_DEBIT,
    /** Passif : credit - debit. */
    LIABILITY_CREDIT,
    /** Flux de tresorerie : positif = encaissement, negatif = decaissement. */
    FLOW_ALGEBRAIC
}
