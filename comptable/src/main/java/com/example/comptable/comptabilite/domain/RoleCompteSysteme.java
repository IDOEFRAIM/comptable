package com.example.comptable.comptabilite.domain;

/** Role technique joue par un compte du referentiel dans les traitements automatiques (TVA,
 * dotations, resultat, racines tiers) - remplace les lookups par numero code en dur disperses
 * dans plusieurs services (voir AccountingAccountResolver). */
public enum RoleCompteSysteme {
    TVA_COLLECTEE,
    TVA_DEDUCTIBLE,
    DOTATIONS_AMORTISSEMENTS,
    RESULTAT_BENEFICE,
    RESULTAT_PERTE,
    RACINE_CLIENT,
    RACINE_FOURNISSEUR,
    /** Compte de tresorerie pour un reglement par cheque/virement/carte/mobile money (voir
     * comptabilite.operation.reglement.ReglementRule) - jamais "521" code en dur. */
    TRESORERIE_BANQUE,
    /** Compte de tresorerie pour un reglement en especes. */
    TRESORERIE_CAISSE
}
