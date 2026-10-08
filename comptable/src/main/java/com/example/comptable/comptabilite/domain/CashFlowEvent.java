package com.example.comptable.comptabilite.domain;

/**
 * Evenement de tresorerie declare sur une ecriture (financement ou investissement). Le montant du flux est celui des lignes de contrepartie de l'evenement dans l'ecriture
 * (jamais estime) ; la ligne du TFT visee est portee par {@link #ligneTft()}. Une ecriture sans ligne de tresorerie n'a aucun flux : inutile de la qualifier.
 */
public enum CashFlowEvent {
    CAPITAL_CASH_INCREASE("FK", true),
    INVESTMENT_GRANT_CASH_RECEIVED("FL", true),
    CAPITAL_CASH_WITHDRAWAL("FM", false),
    DIVIDEND_CASH_PAID("FN", false),
    BORROWING_CASH_RECEIVED("FO", true),
    OTHER_FINANCIAL_DEBT_CASH_RECEIVED("FP", true),
    BORROWING_OR_LEASE_DEBT_CASH_REPAID("FQ", false),
    INTANGIBLE_ASSET_ACQUISITION_CASH_PAID("FF", false),
    TANGIBLE_ASSET_ACQUISITION_CASH_PAID("FG", false),
    FINANCIAL_ASSET_ACQUISITION_CASH_PAID("FH", false),
    TANGIBLE_INTANGIBLE_ASSET_SALE_CASH_RECEIVED("FI", true),
    FINANCIAL_ASSET_SALE_CASH_RECEIVED("FJ", true),
    /** Declaration explicite : le mouvement de tresorerie de cette ecriture n'est ni un flux d'investissement ni un flux de financement (reste dans l'exploitation). */
    NO_TFT_FLOW(null, false);

    private final String ligneTft;
    private final boolean encaissement;

    CashFlowEvent(String ligneTft, boolean encaissement) {
        this.ligneTft = ligneTft;
        this.encaissement = encaissement;
    }

    /** Libelle francais affiche au comptable (la source de verite des valeurs reste cet enum). */
    public String libelle() {
        return switch (this) {
            case CAPITAL_CASH_INCREASE -> "Augmentation de capital en numeraire";
            case INVESTMENT_GRANT_CASH_RECEIVED -> "Subvention d'investissement encaissee";
            case CAPITAL_CASH_WITHDRAWAL -> "Prelevement sur le capital";
            case DIVIDEND_CASH_PAID -> "Dividendes verses";
            case BORROWING_CASH_RECEIVED -> "Nouvel emprunt encaisse";
            case OTHER_FINANCIAL_DEBT_CASH_RECEIVED -> "Autre dette financiere encaissee";
            case BORROWING_OR_LEASE_DEBT_CASH_REPAID -> "Remboursement d'emprunt ou de dette de location-acquisition";
            case INTANGIBLE_ASSET_ACQUISITION_CASH_PAID -> "Acquisition d'immobilisation incorporelle payee";
            case TANGIBLE_ASSET_ACQUISITION_CASH_PAID -> "Acquisition d'immobilisation corporelle payee";
            case FINANCIAL_ASSET_ACQUISITION_CASH_PAID -> "Acquisition d'immobilisation financiere payee";
            case TANGIBLE_INTANGIBLE_ASSET_SALE_CASH_RECEIVED -> "Cession d'immobilisation corporelle ou incorporelle encaissee";
            case FINANCIAL_ASSET_SALE_CASH_RECEIVED -> "Cession d'immobilisation financiere encaissee";
            case NO_TFT_FLOW -> "Aucun flux d'investissement ni de financement";
        };
    }

    public String ligneTft() {
        return ligneTft;
    }

    /** {@code true} : entree de tresorerie ; {@code false} : sortie. */
    public boolean encaissement() {
        return encaissement;
    }
}
