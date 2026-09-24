package com.example.comptable.stock.domain;

/**
 * {@code ENTREE}/{@code SORTIE}/{@code AJUSTEMENT} sont les valeurs historiques : {@code ENTREE}/
 * {@code SORTIE} restent utilisees telles quelles par les reintegrations de stock qui ne
 * correspondent proprement a aucune categorie plus fine ci-dessous (annulation de facture,
 * retour client sur avoir) - les y forcer serait deviner une semantique que le code d'origine ne
 * porte pas. {@code AJUSTEMENT} reste la correction manuelle generique (ecart signe).
 * <p>
 * Les valeurs suivantes portent le sens dans le type (quantite toujours strictement positive en
 * base, comme ENTREE/SORTIE), et ne sont creables que par les orchestrateurs internes concernes -
 * jamais directement via l'endpoint generique POST /mouvements-stock :
 * <ul>
 *   <li>{@code ENTREE_ACHAT} : reception d'un bon de commande fournisseur ({@code CommandeAchatService}).</li>
 *   <li>{@code SORTIE_VENTE} : destockage automatique d'une ligne de facture ({@code FacturationService}).</li>
 *   <li>{@code ENTREE_INVENTAIRE}/{@code SORTIE_INVENTAIRE} : regularisation d'un ecart d'inventaire
 *       physique ({@code InventaireService}, comptabilisee via AccountingOperationEngine).</li>
 *   <li>{@code ENTREE_PRODUCTION}/{@code SORTIE_PRODUCTION}/{@code SORTIE_CONSOMMATION} : prepares
 *       pour un futur moteur de production/nomenclatures - aucune logique n'est cablee dessus dans
 *       cette mission (hors perimetre).</li>
 *   <li>{@code TRANSFERT} : deplacement inter-depots, tracabilite seule (aucun impact sur la
 *       quantite/valeur globale de l'article, aucune ecriture generee).</li>
 * </ul>
 */
public enum TypeMouvementStock {
    ENTREE,
    SORTIE,
    AJUSTEMENT,
    ENTREE_ACHAT,
    ENTREE_PRODUCTION,
    ENTREE_INVENTAIRE,
    SORTIE_VENTE,
    SORTIE_CONSOMMATION,
    SORTIE_PRODUCTION,
    SORTIE_INVENTAIRE,
    TRANSFERT
}
