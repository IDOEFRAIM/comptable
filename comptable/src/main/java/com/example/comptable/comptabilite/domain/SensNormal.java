package com.example.comptable.comptabilite.domain;

/** Sens comptable normal d'un compte. Distinct de {@link NatureCompte} : un compte de contre-actif
 * (ex. 281 Amortissements) est de nature ACTIF mais porte normalement un solde CREDIT. */
public enum SensNormal {
    DEBIT,
    CREDIT
}
