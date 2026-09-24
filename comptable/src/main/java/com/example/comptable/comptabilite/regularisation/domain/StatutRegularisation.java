package com.example.comptable.comptabilite.regularisation.domain;

public enum StatutRegularisation {
    /** Saisie, pas encore proposee au moteur comptable. */
    BROUILLON,
    /** Proposition d'ecriture generee (AccountingOperationEngine), en attente de validation humaine. */
    PROPOSEE,
    /** Proposition validee, ecriture reelle creee. */
    VALIDEE,
    /** Proposition rejetee. */
    REJETEE
}
