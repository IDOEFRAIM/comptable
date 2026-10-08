package com.example.comptable.comptabilite.domain;

import com.example.comptable.comptabilite.operation.common.AccountingOperationType;

/**
 * Nature comptable d'une ecriture : dans quelle vue de reporting elle entre (voir {@code docs/reporting-view-policy.md}).
 * La nature est portee par la donnee (colonne), jamais deduite d'un code de journal, d'un libelle ou d'un prefixe de compte.
 */
public enum NatureEcriture {
    /** Activite courante de la periode. */
    BUSINESS,
    /** Ecriture d'inventaire (regularisation, provision, depreciation, dotation aux amortissements). */
    ADJUSTMENT,
    /** Determination du resultat de cloture : vraie ecriture du grand livre, mais pas de l'activite economique de la periode. */
    CLOSING,
    /** Reserve : aucune ecriture d'a-nouveaux n'est produite aujourd'hui (les soldes de bilan se reportent par cumul historique). */
    OPENING;

    /** Nature deduite du type d'operation qui produit l'ecriture (le moteur d'operations est le seul producteur d'ecritures non manuelles). */
    public static NatureEcriture pour(AccountingOperationType type) {
        if (type == null) {
            return BUSINESS;
        }
        return switch (type) {
            case CLOTURE_EXERCICE -> CLOSING;
            case REGULARISATION, REGULARISATION_REPRISE, REGULARISATION_INVENTAIRE, PROVISION, DEPRECIATION, AMORTISSEMENT_DOTATION -> ADJUSTMENT;
            default -> BUSINESS;
        };
    }
}
