package com.example.comptable.stock.domain;

/** Nature d'un {@link Article}. Ne determine aucune resolution automatique de compte - les 4
 * comptes de l'article restent toujours choisis explicitement a la creation. */
public enum TypeArticle {
    MARCHANDISE,
    MATIERE_PREMIERE,
    PRODUIT_FINI,
    PRODUIT_INTERMEDIAIRE,
    AUTRE
}
