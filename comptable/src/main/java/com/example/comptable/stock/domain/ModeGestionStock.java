package com.example.comptable.stock.domain;

/** PERMANENT : la quantite/valeur de l'{@link Article} est mise a jour en continu a chaque
 * mouvement (comportement historique de ce moteur). INTERMITTENT : aucun mouvement de stock n'est
 * genere lors des achats/ventes (les charges/produits restent comptabilises normalement) - seule
 * une regularisation d'inventaire periodique ajuste la valeur du stock, sur la base du dernier
 * inventaire valide plutot que d'un solde suivi en continu. */
public enum ModeGestionStock {
    PERMANENT,
    INTERMITTENT
}
