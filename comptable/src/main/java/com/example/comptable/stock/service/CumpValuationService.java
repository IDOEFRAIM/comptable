package com.example.comptable.stock.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** Seule methode de valorisation implementee a ce jour : delegue integralement a
 * {@link PumpCalculator}, zero changement de comportement par rapport au calcul historique
 * (voir PumpCalculatorTest, qui reste la source de verite du calcul). */
@Service
class CumpValuationService implements StockValuationService {

    @Override
    public PumpCalculator.ResultatEntree appliquerEntree(BigDecimal quantiteActuelle, BigDecimal valeurActuelle,
                                                          BigDecimal quantiteEntree, BigDecimal prixUnitaireEntree) {
        return PumpCalculator.appliquerEntree(quantiteActuelle, valeurActuelle, quantiteEntree, prixUnitaireEntree);
    }

    @Override
    public PumpCalculator.ResultatSortie appliquerSortie(BigDecimal quantiteActuelle, BigDecimal valeurActuelle,
                                                          BigDecimal coutUnitaireActuel, BigDecimal quantiteSortie) {
        return PumpCalculator.appliquerSortie(quantiteActuelle, valeurActuelle, coutUnitaireActuel, quantiteSortie);
    }
}
