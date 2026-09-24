package com.example.comptable.stock.domain;

/** Comportement du moteur de stock face a une sortie/perte qui depasserait le stock disponible
 * d'un {@link Article}. Le defaut (INTERDIT) preserve le comportement historique du moteur
 * (StockInsuffisantException systematique) - ce n'est jamais une politique universelle imposee. */
public enum PolitiqueStockNegatif {
    INTERDIT,
    AUTORISE_AVEC_ALERTE,
    AUTORISE
}
