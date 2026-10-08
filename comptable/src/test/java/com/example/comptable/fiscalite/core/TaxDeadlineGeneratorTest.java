package com.example.comptable.fiscalite.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Les échéances ne viennent que d'une règle fournie par un cadre ; les valeurs ci-dessous sont des FIXTURES TEST_ONLY, jamais une règle fiscale. */
class TaxDeadlineGeneratorTest {

    private static VatRules regles(String periodicite, VatRules.DueRule echeance) {
        return new VatRules(List.of(new VatRules.Rate("TEST_ONLY_T", "Taux fictif de test", new BigDecimal("1"))), periodicite, echeance, null, null, List.of());
    }

    @Test
    void cadreSansRegleDEcheance_aucuneEcheance() {
        assertThat(TaxDeadlineGenerator.periodesAnnuelles(regles("MONTHLY", null), 2026)).isEmpty();
        assertThat(TaxDeadlineGenerator.echeance(regles("MONTHLY", null), LocalDate.of(2026, 1, 31))).isNull();
    }

    @Test
    void aucunCadre_aucuneEcheance_etAucuneConventionParDefaut() {
        assertThat(TaxDeadlineGenerator.periodesAnnuelles(null, 2026)).isEmpty();
        assertThat(TaxDeadlineGenerator.echeance(null, LocalDate.of(2026, 1, 31))).isNull();
    }

    @Test
    void periodiciteNonMensuelle_aucunePeriodeGeneree() {
        assertThat(TaxDeadlineGenerator.periodesAnnuelles(regles("QUARTERLY", new VatRules.DueRule(1, 20)), 2026)).isEmpty();
    }

    @Test
    void cadreFictifAvecRegle_echeanceCalculeeDepuisLaRegleDuCadre() {
        // règle fictive TEST_ONLY : 2 mois après la fin de période, le 7
        var periodes = TaxDeadlineGenerator.periodesAnnuelles(regles("MONTHLY", new VatRules.DueRule(2, 7)), 2026);
        assertThat(periodes).hasSize(12);
        assertThat(periodes.get(0).periode()).isEqualTo("2026-01");
        assertThat(periodes.get(0).dateEcheance()).isEqualTo(LocalDate.of(2026, 3, 7));
        assertThat(periodes.get(11).dateEcheance()).isEqualTo(LocalDate.of(2027, 2, 7));
    }

    @Test
    void jourDuMoisBorneAuDernierJourDuMois() {
        assertThat(TaxDeadlineGenerator.echeance(regles("MONTHLY", new VatRules.DueRule(1, 31)), LocalDate.of(2026, 1, 31))).isEqualTo(LocalDate.of(2026, 2, 28));
    }
}
