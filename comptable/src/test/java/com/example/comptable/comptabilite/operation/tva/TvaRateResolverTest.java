package com.example.comptable.comptabilite.operation.tva;

import com.example.comptable.fiscalite.regles.domain.TaxRule;
import com.example.comptable.fiscalite.regles.repository.TaxRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Phase A : le resolveur TVA ne traite plus que la FISCALITE (taux, montant). Le compte comptable de TVA
 * releve exclusivement d'AccountingAccountResolver ; les cas « compte non configure » sont testes dans
 * AchatRuleTest / VenteRuleTest.
 */
@ExtendWith(MockitoExtension.class)
class TvaRateResolverTest {

    @Mock
    private TaxRuleRepository taxRuleRepository;

    @InjectMocks
    private TvaRateResolver tvaRateResolver;

    private TaxRule regle(BigDecimal taux, LocalDate from, LocalDate to, String source) {
        TaxRule regle = new TaxRule();
        regle.setId(UUID.randomUUID());
        regle.setPays("CI");
        regle.setCode(TvaRateResolver.CODE_TVA);
        regle.setRegime("REEL_NORMAL");
        regle.setLibelle("TVA normale");
        regle.setTaux(taux);
        regle.setEffectiveFrom(from);
        regle.setEffectiveTo(to);
        regle.setSource(source);
        return regle;
    }

    @Test
    void calcule_la_tva_a_partir_de_la_regle_effective_a_la_date() {
        TaxRule regle = regle(new BigDecimal("18"), LocalDate.of(2020, 1, 1), null, "REF_OFFICIELLE_DGI_CI");
        when(taxRuleRepository.findByPaysAndCode("CI", TvaRateResolver.CODE_TVA)).thenReturn(List.of(regle));

        TvaResultat resultat = tvaRateResolver.resoudre("CI", LocalDate.of(2026, 3, 1), new BigDecimal("100000"));

        assertThat(resultat.nonSupporte()).isFalse();
        assertThat(resultat.taux()).isEqualByComparingTo("18");
        assertThat(resultat.montantTva()).isEqualByComparingTo("18000");
        assertThat(resultat.taxRuleId()).isEqualTo(regle.getId());
        assertThat(resultat.sourceDemo()).isFalse();
    }

    @Test
    void marque_sourcedemo_quand_la_source_est_une_fixture_de_demonstration() {
        TaxRule regle = regle(new BigDecimal("18"), LocalDate.of(2020, 1, 1), null, "FIXTURE_DEMO_NON_VERIFIE");
        when(taxRuleRepository.findByPaysAndCode("CI", TvaRateResolver.CODE_TVA)).thenReturn(List.of(regle));

        assertThat(tvaRateResolver.resoudre("CI", LocalDate.of(2026, 3, 1), new BigDecimal("100000")).sourceDemo()).isTrue();
    }

    @Test
    void aucune_regle_applicable_retourne_non_supporte_jamais_zero() {
        when(taxRuleRepository.findByPaysAndCode("CI", TvaRateResolver.CODE_TVA)).thenReturn(List.of());

        TvaResultat resultat = tvaRateResolver.resoudre("CI", LocalDate.of(2026, 3, 1), new BigDecimal("100000"));

        assertThat(resultat.nonSupporte()).isTrue();
        assertThat(resultat.montantTva()).isNull();
        assertThat(resultat.motif()).isNotBlank();
    }

    @Test
    void regle_hors_periode_effective_est_ignoree() {
        TaxRule ancienne = regle(new BigDecimal("15"), LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31), "REF_OFFICIELLE");
        when(taxRuleRepository.findByPaysAndCode("CI", TvaRateResolver.CODE_TVA)).thenReturn(List.of(ancienne));

        assertThat(tvaRateResolver.resoudre("CI", LocalDate.of(2026, 3, 1), new BigDecimal("100000")).nonSupporte()).isTrue();
    }

    @Test
    void resoudre_par_regle_ignore_une_regle_sans_taux() {
        TaxRule sansTaux = regle(null, LocalDate.of(2020, 1, 1), null, "REF_OFFICIELLE");
        when(taxRuleRepository.findById(sansTaux.getId())).thenReturn(Optional.of(sansTaux));

        TvaResultat resultat = tvaRateResolver.resoudreParRegle(sansTaux.getId(), new BigDecimal("100000"));

        assertThat(resultat.nonSupporte()).isTrue();
        assertThat(resultat.motif()).contains("sans taux");
    }

    @Test
    void resoudre_par_regle_explicite_calcule_le_montant() {
        TaxRule regle = regle(new BigDecimal("18"), LocalDate.of(2020, 1, 1), null, "REF_OFFICIELLE");
        when(taxRuleRepository.findById(regle.getId())).thenReturn(Optional.of(regle));

        TvaResultat resultat = tvaRateResolver.resoudreParRegle(regle.getId(), new BigDecimal("50000"));

        assertThat(resultat.nonSupporte()).isFalse();
        assertThat(resultat.montantTva()).isEqualByComparingTo("9000");
    }
}
