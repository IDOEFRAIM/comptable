package com.example.comptable.comptabilite.operation.tva;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.service.CompteSystemeResolver;
import com.example.comptable.fiscalite.regles.domain.TaxRule;
import com.example.comptable.fiscalite.regles.repository.TaxRuleRepository;
import org.junit.jupiter.api.BeforeEach;
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

@ExtendWith(MockitoExtension.class)
class TvaRateResolverTest {

    @Mock
    private TaxRuleRepository taxRuleRepository;
    @Mock
    private CompteSystemeResolver compteSystemeResolver;

    @InjectMocks
    private TvaRateResolver tvaRateResolver;

    private UUID entrepriseId;
    private CompteComptable compteTva;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        compteTva = new CompteComptable();
        compteTva.setId(UUID.randomUUID());
        compteTva.setNumero("443");
    }

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
        when(compteSystemeResolver.resoudre(entrepriseId, RoleCompteSysteme.TVA_COLLECTEE)).thenReturn(Optional.of(compteTva));

        TvaResultat resultat = tvaRateResolver.resoudre(entrepriseId, "CI", LocalDate.of(2026, 3, 1),
                new BigDecimal("100000"), RoleCompteSysteme.TVA_COLLECTEE);

        assertThat(resultat.nonSupporte()).isFalse();
        assertThat(resultat.montantTva()).isEqualByComparingTo("18000");
        assertThat(resultat.compte()).isSameAs(compteTva);
        assertThat(resultat.sourceDemo()).isFalse();
    }

    @Test
    void marque_sourcedemo_quand_la_source_est_une_fixture_de_demonstration() {
        TaxRule regle = regle(new BigDecimal("18"), LocalDate.of(2020, 1, 1), null, "FIXTURE_DEMO_NON_VERIFIE");
        when(taxRuleRepository.findByPaysAndCode("CI", TvaRateResolver.CODE_TVA)).thenReturn(List.of(regle));
        when(compteSystemeResolver.resoudre(entrepriseId, RoleCompteSysteme.TVA_COLLECTEE)).thenReturn(Optional.of(compteTva));

        TvaResultat resultat = tvaRateResolver.resoudre(entrepriseId, "CI", LocalDate.of(2026, 3, 1),
                new BigDecimal("100000"), RoleCompteSysteme.TVA_COLLECTEE);

        assertThat(resultat.sourceDemo()).isTrue();
    }

    @Test
    void aucune_regle_applicable_retourne_non_supporte_jamais_zero() {
        when(taxRuleRepository.findByPaysAndCode("CI", TvaRateResolver.CODE_TVA)).thenReturn(List.of());

        TvaResultat resultat = tvaRateResolver.resoudre(entrepriseId, "CI", LocalDate.of(2026, 3, 1),
                new BigDecimal("100000"), RoleCompteSysteme.TVA_COLLECTEE);

        assertThat(resultat.nonSupporte()).isTrue();
        assertThat(resultat.montantTva()).isNull();
        assertThat(resultat.motif()).isNotBlank();
    }

    @Test
    void regle_hors_periode_effective_est_ignoree() {
        TaxRule ancienneRegle = regle(new BigDecimal("15"), LocalDate.of(2015, 1, 1), LocalDate.of(2019, 12, 31), "REF_OFFICIELLE");
        when(taxRuleRepository.findByPaysAndCode("CI", TvaRateResolver.CODE_TVA)).thenReturn(List.of(ancienneRegle));

        TvaResultat resultat = tvaRateResolver.resoudre(entrepriseId, "CI", LocalDate.of(2026, 3, 1),
                new BigDecimal("100000"), RoleCompteSysteme.TVA_COLLECTEE);

        assertThat(resultat.nonSupporte()).isTrue();
    }

    @Test
    void compte_systeme_non_configure_retourne_non_supporte() {
        TaxRule regle = regle(new BigDecimal("18"), LocalDate.of(2020, 1, 1), null, "REF_OFFICIELLE");
        when(taxRuleRepository.findByPaysAndCode("CI", TvaRateResolver.CODE_TVA)).thenReturn(List.of(regle));
        when(compteSystemeResolver.resoudre(entrepriseId, RoleCompteSysteme.TVA_COLLECTEE)).thenReturn(Optional.empty());

        TvaResultat resultat = tvaRateResolver.resoudre(entrepriseId, "CI", LocalDate.of(2026, 3, 1),
                new BigDecimal("100000"), RoleCompteSysteme.TVA_COLLECTEE);

        assertThat(resultat.nonSupporte()).isTrue();
        assertThat(resultat.motif()).contains("TVA_COLLECTEE");
    }

    @Test
    void resoudre_par_regle_ignore_une_regle_sans_taux() {
        TaxRule regleSansTaux = regle(null, LocalDate.of(2020, 1, 1), null, "REF_OFFICIELLE");
        when(taxRuleRepository.findById(regleSansTaux.getId())).thenReturn(Optional.of(regleSansTaux));

        TvaResultat resultat = tvaRateResolver.resoudreParRegle(entrepriseId, regleSansTaux.getId(),
                new BigDecimal("100000"), RoleCompteSysteme.TVA_DEDUCTIBLE);

        assertThat(resultat.nonSupporte()).isTrue();
        assertThat(resultat.motif()).contains("sans taux");
    }

    @Test
    void resoudre_par_regle_explicite_calcule_avec_le_compte_systeme_resolu() {
        TaxRule regle = regle(new BigDecimal("18"), LocalDate.of(2020, 1, 1), null, "REF_OFFICIELLE");
        when(taxRuleRepository.findById(regle.getId())).thenReturn(Optional.of(regle));
        when(compteSystemeResolver.resoudre(entrepriseId, RoleCompteSysteme.TVA_DEDUCTIBLE)).thenReturn(Optional.of(compteTva));

        TvaResultat resultat = tvaRateResolver.resoudreParRegle(entrepriseId, regle.getId(),
                new BigDecimal("50000"), RoleCompteSysteme.TVA_DEDUCTIBLE);

        assertThat(resultat.nonSupporte()).isFalse();
        assertThat(resultat.montantTva()).isEqualByComparingTo("9000");
    }
}
