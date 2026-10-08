package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.core.TaxPeriodStatus;
import com.example.comptable.fiscalite.vat.VatOperationalStatusEvaluator.Input;
import com.example.comptable.fiscalite.vat.VatOperationalStatusEvaluator.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Évaluateur unique du statut opérationnel : faits techniques uniquement (aucune règle fiscale). Les montants sont de simples valeurs de test. */
class VatOperationalStatusEvaluatorTest {

    private static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    private static Input in(TaxPeriodStatus statut, boolean cadre, int aRevoir, int anomalies, boolean complet, BigDecimal net, BigDecimal paye, boolean report, boolean finalisable) {
        return new Input(statut, cadre, aRevoir, anomalies, complet, BigDecimal.ZERO, net, paye, report, finalisable, 10);
    }

    @Test
    void cycleDeLaPreparation_aPreparer_enPreparation_attentionRequise_pretPourRevue() {
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.OPEN, true, 0, 0, true, null, null, false, false)).status()).isEqualTo("A_PREPARER");
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.PREPARING, true, 0, 0, true, null, null, false, false)).status()).isEqualTo("EN_PREPARATION");
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.PREPARING, true, 3, 0, true, null, null, false, false)).status()).isEqualTo("ATTENTION_REQUISE");
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.PREPARING, true, 0, 2, true, null, null, false, false)).status()).isEqualTo("ATTENTION_REQUISE");
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.READY_FOR_REVIEW, false, 0, 0, false, null, null, false, false)).status()).isEqualTo("PRET_POUR_REVUE");
    }

    @Test
    void periodeRevue_prêteAFinaliserSeulementSiLeBackendLaDitFinalisable_sinonEnRevue() {
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.REVIEWED, true, 0, 0, true, null, null, false, true)).status()).isEqualTo("PRET_A_FINALISER");
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.REVIEWED, false, 0, 0, false, null, null, false, false)).status()).isEqualTo("EN_REVUE");
    }

    @Test
    void finaliseAvecTvaDue_aPayer_partiellementPaye_paye() {
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.FINAL, true, 0, 0, true, n("1000"), n("0"), false, false)).status()).isEqualTo("A_PAYER");
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.FINAL, true, 0, 0, true, n("1000"), n("400"), false, false)).status()).isEqualTo("PARTIELLEMENT_PAYE");
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.FINAL, true, 0, 0, true, n("1000"), n("1000"), false, false)).status()).isEqualTo("PAYE");
    }

    @Test
    void creditAReporter_neSeLitQueSiLeCadreVerifiePrevoitLeReport_sinonFinaliseEtCreditARevoir() {
        Result sansRegle = VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.FINAL, true, 0, 0, true, n("-250"), n("0"), false, false));
        assertThat(sansRegle.status()).isEqualTo("FINALISE");
        assertThat(sansRegle.creditState()).isEqualTo("TO_REVIEW");
        Result avecRegle = VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.FINAL, true, 0, 0, true, n("-250"), n("0"), true, false));
        assertThat(avecRegle.status()).isEqualTo("CREDIT_A_REPORTER");
        assertThat(avecRegle.creditState()).isEqualTo("REPORTABLE");
        assertThat(VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.FINAL, true, 0, 0, true, n("0"), n("0"), false, false)).status()).isEqualTo("FINALISE");
    }

    @Test
    void progression_huitEtapesJamaisUnPourcentage_etEtapesBloquantes() {
        Result r = VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.PREPARING, false, 2, 1, false, null, null, false, false));
        assertThat(r.totalSteps()).isEqualTo(8);
        assertThat(r.steps()).extracting(VatOperationalStatusEvaluator.Step::code).containsExactly("OPERATIONS_CHARGEES", "TRAITEMENTS_DECIDES", "CONTROLES", "AJUSTEMENTS", "DECLARATION", "REVUE", "FINALISATION",
                "PAIEMENT_CREDIT");
        assertThat(r.readySteps()).isEqualTo(2);                                                                          // opérations chargées + ajustements
        assertThat(r.blockingSteps()).containsExactly("TRAITEMENTS_DECIDES", "CONTROLES", "DECLARATION");

        Result fini = VatOperationalStatusEvaluator.evaluer(in(TaxPeriodStatus.FINAL, true, 0, 0, true, n("100"), n("100"), false, false));
        assertThat(fini.readySteps()).isEqualTo(8);
        assertThat(fini.blockingSteps()).isEmpty();
    }

    @Test
    void portefeuille_neMontrePasPreteOuFinalisableSansCadreVerifie_etIndiqueLeReglement() {
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.REVIEWED, false, 0, null, null, false)).isEqualTo("CADRE_INCOMPLET");
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.REVIEWED, true, 0, null, null, false)).isEqualTo("PRETE");
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.PREPARING, true, 3, null, null, false)).isEqualTo("A_TRAITER");
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.PREPARING, false, 0, null, null, false)).isEqualTo("CADRE_INCOMPLET");
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.READY_FOR_REVIEW, false, 0, null, null, false)).isEqualTo("A_REVOIR");
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.FINAL, true, 0, n("100"), n("0"), false)).isEqualTo("A_PAYER");
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.FINAL, true, 0, n("100"), n("30"), false)).isEqualTo("PARTIELLEMENT_PAYEE");
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.FINAL, true, 0, n("100"), n("100"), false)).isEqualTo("PAYEE");
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.FINAL, true, 0, n("-5"), n("0"), false)).isEqualTo("FINALISEE");
        assertThat(VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.FINAL, true, 0, n("-5"), n("0"), true)).isEqualTo("CREDIT_A_REPORTER");
    }
}
