package com.example.comptable.comptabilite.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MontantEnLettresTest {

    @Test
    void nombresUsuels() {
        assertThat(MontantEnLettres.enLettres(0)).isEqualTo("Zéro");
        assertThat(MontantEnLettres.enLettres(1)).isEqualTo("Un");
        assertThat(MontantEnLettres.enLettres(16)).isEqualTo("Seize");
        assertThat(MontantEnLettres.enLettres(21)).isEqualTo("Vingt et un");
        assertThat(MontantEnLettres.enLettres(22)).isEqualTo("Vingt-deux");
        assertThat(MontantEnLettres.enLettres(71)).isEqualTo("Soixante et onze");
        assertThat(MontantEnLettres.enLettres(77)).isEqualTo("Soixante-dix-sept");
        assertThat(MontantEnLettres.enLettres(80)).isEqualTo("Quatre-vingts");
        assertThat(MontantEnLettres.enLettres(81)).isEqualTo("Quatre-vingt-un");
        assertThat(MontantEnLettres.enLettres(91)).isEqualTo("Quatre-vingt-onze");
        assertThat(MontantEnLettres.enLettres(99)).isEqualTo("Quatre-vingt-dix-neuf");
    }

    @Test
    void centsEtMilliers_laRegleDuS() {
        assertThat(MontantEnLettres.enLettres(100)).isEqualTo("Cent");
        assertThat(MontantEnLettres.enLettres(101)).isEqualTo("Cent un");
        assertThat(MontantEnLettres.enLettres(200)).isEqualTo("Deux cents");
        assertThat(MontantEnLettres.enLettres(280)).isEqualTo("Deux cent quatre-vingts");
        assertThat(MontantEnLettres.enLettres(1000)).isEqualTo("Mille");
        assertThat(MontantEnLettres.enLettres(1001)).isEqualTo("Mille un");
        assertThat(MontantEnLettres.enLettres(1254)).isEqualTo("Mille deux cent cinquante-quatre");
        assertThat(MontantEnLettres.enLettres(80_000)).as("pas de s devant mille").isEqualTo("Quatre-vingt mille");
        assertThat(MontantEnLettres.enLettres(200_000)).isEqualTo("Deux cent mille");
        assertThat(MontantEnLettres.enLettres(128_000)).isEqualTo("Cent vingt-huit mille");
    }

    @Test
    void millionsEtMilliards() {
        assertThat(MontantEnLettres.enLettres(1_000_000)).isEqualTo("Un million");
        assertThat(MontantEnLettres.enLettres(2_000_000)).isEqualTo("Deux millions");
        assertThat(MontantEnLettres.enLettres(200_000_000)).as("s devant million").isEqualTo("Deux cents millions");
        assertThat(MontantEnLettres.enLettres(1_250_300)).isEqualTo("Un million deux cent cinquante mille trois cents");
        assertThat(MontantEnLettres.enLettres(3_000_000_000L)).isEqualTo("Trois milliards");
    }

    @Test
    void montantsAvecDevise() {
        assertThat(MontantEnLettres.montant(new BigDecimal("128000"), "XOF")).isEqualTo("Cent vingt-huit mille francs CFA");
        assertThat(MontantEnLettres.montant(new BigDecimal("1000.60"), "XOF")).as("le franc CFA n'a pas de décimales").isEqualTo("Mille un francs CFA");
        assertThat(MontantEnLettres.montant(new BigDecimal("1254.30"), "EUR")).isEqualTo("Mille deux cent cinquante-quatre euros et trente centimes");
    }
}
