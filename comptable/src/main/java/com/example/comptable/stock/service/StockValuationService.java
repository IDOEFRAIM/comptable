package com.example.comptable.stock.service;

import java.math.BigDecimal;

/**
 * Abstraction de la methode de valorisation du stock, pour permettre d'ajouter une methode
 * alternative plus tard sans reecrire {@link MouvementStockService} ni les mouvements deja
 * enregistres.
 * <p>
 * <b>IMPLEMENTE</b> : CUMP/PUMP (Cout/Prix Unitaire Moyen Pondere), via {@link CumpValuationService}.
 * <b>NON IMPLEMENTE</b> : FIFO, LIFO, cout standard - aucune de ces methodes n'a ete suffisamment
 * cadree/referencee pour cette mission ; elles ne sont donc pas devinees ni approximees.
 */
interface StockValuationService {

    PumpCalculator.ResultatEntree appliquerEntree(BigDecimal quantiteActuelle, BigDecimal valeurActuelle,
                                                   BigDecimal quantiteEntree, BigDecimal prixUnitaireEntree);

    PumpCalculator.ResultatSortie appliquerSortie(BigDecimal quantiteActuelle, BigDecimal valeurActuelle,
                                                   BigDecimal coutUnitaireActuel, BigDecimal quantiteSortie);
}
