package com.example.comptable.comptabilite.provision.domain;

/** Mecanique universelle de dotation/reprise, pas une regle SYSCOHADA specifique disputee. */
public enum TypeMouvementProvision {
    /** debit compte de dotation (charge) / credit compte de provision, pour (montantNouveau - montantPrecedent). */
    DOTATION,
    /** debit compte de provision / credit compte de reprise (produit), pour montantPrecedent entier - le risque n'existe plus. */
    REPRISE_TOTALE,
    /** debit compte de provision / credit compte de reprise, pour (montantPrecedent - montantNouveau) - le risque est reduit sans disparaitre. */
    REPRISE_PARTIELLE
}
