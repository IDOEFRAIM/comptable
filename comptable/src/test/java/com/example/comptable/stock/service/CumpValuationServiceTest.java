package com.example.comptable.stock.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifie que l'abstraction StockValuationService delegue exactement a PumpCalculator, sans
 * changer le resultat du calcul deja verifie par PumpCalculatorTest. */
class CumpValuationServiceTest {

    private final StockValuationService service = new CumpValuationService();

    @Test
    void appliquerEntree_delegue_a_pumpCalculator() {
        PumpCalculator.ResultatEntree attendu = PumpCalculator.appliquerEntree(
                new BigDecimal("10"), new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("140"));

        PumpCalculator.ResultatEntree resultat = service.appliquerEntree(
                new BigDecimal("10"), new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("140"));

        assertThat(resultat.nouveauPump()).isEqualByComparingTo(attendu.nouveauPump());
        assertThat(resultat.nouvelleQuantite()).isEqualByComparingTo(attendu.nouvelleQuantite());
        assertThat(resultat.nouvelleValeur()).isEqualByComparingTo(attendu.nouvelleValeur());
    }

    @Test
    void appliquerSortie_delegue_a_pumpCalculator() {
        PumpCalculator.ResultatSortie attendu = PumpCalculator.appliquerSortie(
                new BigDecimal("20"), new BigDecimal("2400"), new BigDecimal("120"), new BigDecimal("5"));

        PumpCalculator.ResultatSortie resultat = service.appliquerSortie(
                new BigDecimal("20"), new BigDecimal("2400"), new BigDecimal("120"), new BigDecimal("5"));

        assertThat(resultat.valeurSortie()).isEqualByComparingTo(attendu.valeurSortie());
        assertThat(resultat.nouvelleQuantite()).isEqualByComparingTo(attendu.nouvelleQuantite());
        assertThat(resultat.nouvelleValeur()).isEqualByComparingTo(attendu.nouvelleValeur());
    }
}
