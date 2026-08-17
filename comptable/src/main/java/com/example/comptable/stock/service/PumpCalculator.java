package com.example.comptable.stock.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calcul pur du PUMP (Prix Unitaire Moyen Pondere), sans effet de bord ni persistance -
 * separe de {@link MouvementStockService} a l'image de PlanAmortissementCalculator pour
 * l'amortissement lineaire : la formule merite d'etre testable independamment du reste
 * (chargement d'article, generation d'ecriture...).
 * <p>
 * Regle d'entree : {@code nouveauPump = (valeurStockActuelle + quantiteEntree * prixEntree)
 * / (quantiteStockActuelle + quantiteEntree)}.
 * <p>
 * Regle de sortie : le PUMP en vigueur ne change jamais lors d'une sortie ; seules la quantite
 * et la valeur totale du stock diminuent, valorisees au PUMP courant.
 */
final class PumpCalculator {

    private PumpCalculator() {
    }

    record ResultatEntree(BigDecimal nouveauPump, BigDecimal nouvelleQuantite, BigDecimal nouvelleValeur, BigDecimal valeurEntree) {
    }

    static ResultatEntree appliquerEntree(BigDecimal quantiteActuelle, BigDecimal valeurActuelle,
                                           BigDecimal quantiteEntree, BigDecimal prixUnitaireEntree) {
        BigDecimal valeurEntree = quantiteEntree.multiply(prixUnitaireEntree).setScale(2, RoundingMode.HALF_UP);
        BigDecimal nouvelleValeur = valeurActuelle.add(valeurEntree);
        BigDecimal nouvelleQuantite = quantiteActuelle.add(quantiteEntree);
        BigDecimal nouveauPump = nouvelleQuantite.signum() == 0
                ? BigDecimal.ZERO
                : nouvelleValeur.divide(nouvelleQuantite, 4, RoundingMode.HALF_UP);
        return new ResultatEntree(nouveauPump, nouvelleQuantite, nouvelleValeur, valeurEntree);
    }

    record ResultatSortie(BigDecimal valeurSortie, BigDecimal nouvelleQuantite, BigDecimal nouvelleValeur) {
    }

    static ResultatSortie appliquerSortie(BigDecimal quantiteActuelle, BigDecimal valeurActuelle,
                                           BigDecimal pumpActuel, BigDecimal quantiteSortie) {
        BigDecimal valeurSortie = quantiteSortie.multiply(pumpActuel).setScale(2, RoundingMode.HALF_UP);
        BigDecimal nouvelleQuantite = quantiteActuelle.subtract(quantiteSortie);
        BigDecimal nouvelleValeur = valeurActuelle.subtract(valeurSortie);
        return new ResultatSortie(valeurSortie, nouvelleQuantite, nouvelleValeur);
    }
}
