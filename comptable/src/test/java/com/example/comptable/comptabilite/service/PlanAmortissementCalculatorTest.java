package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.ModeAmortissement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanAmortissementCalculatorTest {

    @Test
    void lineaire_applique_le_prorata_temporis_sur_lannee_dacquisition_et_reporte_le_reliquat() {
        // Acquisition en mars : 10 mois restants sur 12 (mois d'acquisition inclus).
        List<PlanAmortissementCalculator.LigneAmortissement> plan = PlanAmortissementCalculator.calculer(
                new BigDecimal("120000"), LocalDate.of(2027, 3, 15), 5, ModeAmortissement.LINEAIRE);

        // 6 lignes : 5 annees pleines + 1 annee de reliquat pour absorber le prorata de l'annee 1.
        assertThat(plan).hasSize(6);
        assertThat(plan.get(0).annee()).isEqualTo(2027);
        assertThat(plan.get(5).annee()).isEqualTo(2032);
        // Dotation pleine = 120000/5 = 24000 ; annee 1 = 24000 x 10/12 = 20000.
        assertThat(plan.get(0).dotation()).isEqualByComparingTo("20000");
        assertThat(plan.get(1).dotation()).isEqualByComparingTo("24000");
        assertThat(plan.get(4).dotation()).isEqualByComparingTo("24000");
        // Reliquat de la 6e annee = le complement des 4 mois non amortis en annee 1 (24000 x 2/12).
        assertThat(plan.get(5).dotation()).isEqualByComparingTo("4000");
        assertThat(plan.get(5).valeurNetteComptableFin()).isEqualByComparingTo("0");

        BigDecimal total = plan.stream().map(PlanAmortissementCalculator.LigneAmortissement::dotation)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("120000");
    }

    @Test
    void lineaire_sans_prorata_quand_lacquisition_tombe_un_1er_janvier() {
        // Mois restants = 12 : aucun prorata necessaire, comportement identique a une dotation pleine classique.
        List<PlanAmortissementCalculator.LigneAmortissement> plan = PlanAmortissementCalculator.calculer(
                new BigDecimal("120000"), LocalDate.of(2027, 1, 1), 5, ModeAmortissement.LINEAIRE);

        assertThat(plan).hasSize(5);
        plan.forEach(l -> assertThat(l.dotation()).isEqualByComparingTo("24000"));
        assertThat(plan.get(4).valeurNetteComptableFin()).isEqualByComparingTo("0");
    }

    @Test
    void lineaire_absorbe_larrondi_sur_la_derniere_annee() {
        List<PlanAmortissementCalculator.LigneAmortissement> plan = PlanAmortissementCalculator.calculer(
                new BigDecimal("10000"), LocalDate.of(2027, 1, 1), 3, ModeAmortissement.LINEAIRE);

        // 10000 / 3 = 3333.33... arrondi a 3333.33, la 3e ligne absorbe le reliquat pour retomber pile a 10000.
        BigDecimal total = plan.stream().map(PlanAmortissementCalculator.LigneAmortissement::dotation)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("10000");
        assertThat(plan.get(2).valeurNetteComptableFin()).isEqualByComparingTo("0");
    }

    @Test
    void degressif_utilise_le_coefficient_175_pour_une_duree_de_5_ans_et_epuise_la_valeur() {
        List<PlanAmortissementCalculator.LigneAmortissement> plan = PlanAmortissementCalculator.calculer(
                new BigDecimal("100000"), LocalDate.of(2027, 1, 1), 5, ModeAmortissement.DEGRESSIF);

        assertThat(plan).hasSize(5);
        // taux = 1.75 / 5 = 0.35
        assertThat(plan.get(0).dotation()).isEqualByComparingTo("35000.00");
        assertThat(plan.get(0).valeurNetteComptableFin()).isEqualByComparingTo("65000.00");
        assertThat(plan.get(1).dotation()).isEqualByComparingTo("22750.00");
        assertThat(plan.get(4).valeurNetteComptableFin()).isEqualByComparingTo("0");

        BigDecimal total = plan.stream().map(PlanAmortissementCalculator.LigneAmortissement::dotation)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("100000");
    }

    @Test
    void degressif_utilise_le_coefficient_125_pour_une_duree_courte() {
        List<PlanAmortissementCalculator.LigneAmortissement> plan = PlanAmortissementCalculator.calculer(
                new BigDecimal("8000"), LocalDate.of(2027, 1, 1), 4, ModeAmortissement.DEGRESSIF);

        // taux = 1.25 / 4 = 0.3125
        assertThat(plan.get(0).dotation()).isEqualByComparingTo("2500.00");
    }
}
