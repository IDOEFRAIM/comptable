package com.example.comptable.stock.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PumpCalculatorTest {

    @Test
    void une_premiere_entree_sur_stock_vide_fixe_le_pump_au_prix_dachat() {
        PumpCalculator.ResultatEntree resultat = PumpCalculator.appliquerEntree(
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10"), new BigDecimal("1000"));

        assertThat(resultat.nouvelleQuantite()).isEqualByComparingTo("10");
        assertThat(resultat.nouvelleValeur()).isEqualByComparingTo("10000");
        assertThat(resultat.nouveauPump()).isEqualByComparingTo("1000");
    }

    @Test
    void une_deuxieme_entree_a_prix_different_repondere_le_pump() {
        PumpCalculator.ResultatEntree resultat = PumpCalculator.appliquerEntree(
                new BigDecimal("10"), new BigDecimal("10000"), new BigDecimal("10"), new BigDecimal("1400"));

        // (10000 + 10*1400) / 20 = 24000 / 20 = 1200
        assertThat(resultat.nouvelleQuantite()).isEqualByComparingTo("20");
        assertThat(resultat.nouvelleValeur()).isEqualByComparingTo("24000");
        assertThat(resultat.nouveauPump()).isEqualByComparingTo("1200");
    }

    @Test
    void une_sortie_valorise_au_pump_courant_sans_le_modifier() {
        PumpCalculator.ResultatSortie resultat = PumpCalculator.appliquerSortie(
                new BigDecimal("20"), new BigDecimal("24000"), new BigDecimal("1200"), new BigDecimal("5"));

        assertThat(resultat.valeurSortie()).isEqualByComparingTo("6000");
        assertThat(resultat.nouvelleQuantite()).isEqualByComparingTo("15");
        assertThat(resultat.nouvelleValeur()).isEqualByComparingTo("18000");
    }

    @Test
    void une_sortie_qui_vide_totalement_le_stock_ramene_la_valeur_a_zero() {
        PumpCalculator.ResultatSortie resultat = PumpCalculator.appliquerSortie(
                new BigDecimal("15"), new BigDecimal("18000"), new BigDecimal("1200"), new BigDecimal("15"));

        assertThat(resultat.nouvelleQuantite()).isEqualByComparingTo("0");
        assertThat(resultat.nouvelleValeur()).isEqualByComparingTo("0");
    }

    @Test
    void le_pump_est_arrondi_a_4_decimales() {
        PumpCalculator.ResultatEntree resultat = PumpCalculator.appliquerEntree(
                new BigDecimal("3"), new BigDecimal("1000"), new BigDecimal("7"), new BigDecimal("333"));

        // (1000 + 7*333) / 10 = (1000+2331)/10 = 3331/10 = 333.1
        assertThat(resultat.nouveauPump()).isEqualByComparingTo("333.1");
    }
}
