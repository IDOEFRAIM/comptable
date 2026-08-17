package com.example.comptable.fiscalite.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EcheancierTvaGeneratorTest {

    @Test
    void genere_douze_periodes_mensuelles() {
        List<EcheancierTvaGenerator.PeriodeEcheance> periodes = EcheancierTvaGenerator.genererPeriodesAnnuelles(2026);

        assertThat(periodes).hasSize(12);
        assertThat(periodes.get(0).periode()).isEqualTo("2026-01");
        assertThat(periodes.get(11).periode()).isEqualTo("2026-12");
    }

    @Test
    void echeance_est_le_15_du_mois_suivant() {
        List<EcheancierTvaGenerator.PeriodeEcheance> periodes = EcheancierTvaGenerator.genererPeriodesAnnuelles(2026);

        assertThat(periodes.get(0).dateEcheance()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(periodes.get(5).dateEcheance()).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    @Test
    void decembre_bascule_sur_janvier_de_lannee_suivante() {
        List<EcheancierTvaGenerator.PeriodeEcheance> periodes = EcheancierTvaGenerator.genererPeriodesAnnuelles(2026);

        EcheancierTvaGenerator.PeriodeEcheance decembre = periodes.get(11);
        assertThat(decembre.periode()).isEqualTo("2026-12");
        assertThat(decembre.dateEcheance()).isEqualTo(LocalDate.of(2027, 1, 15));
    }
}
