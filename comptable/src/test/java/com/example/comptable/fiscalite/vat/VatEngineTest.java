package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.core.TaxTreatment;
import com.example.comptable.fiscalite.core.VatRules;
import com.example.comptable.fiscalite.vat.VatModel.Adjustment;
import com.example.comptable.fiscalite.vat.VatModel.Computation;
import com.example.comptable.fiscalite.vat.VatModel.Decision;
import com.example.comptable.fiscalite.vat.VatModel.Line;
import com.example.comptable.fiscalite.vat.VatModel.Operation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moteur TVA pur. Les taux utilisés ici (10 et 5) sont des valeurs FICTIVES de test, choisies pour ne ressembler à aucune réglementation : le moteur ne contient aucun taux ; il lit uniquement ceux du cadre
 * qu'on lui donne.
 */
class VatEngineTest {

    private final VatEngine moteur = new VatEngine();
    private static final VatRules CADRE = new VatRules(List.of(new VatRules.Rate("T10", "Taux test A", new BigDecimal("10")), new VatRules.Rate("T5", "Taux test B", new BigDecimal("5"))), "MONTHLY",
            null, new BigDecimal("1"), new BigDecimal("0.5"), List.of());

    private static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    /** Vente : D client TTC / C produit HT / C TVA collectée. */
    private Operation vente(String piece, String ht, String tva) {
        BigDecimal ttc = n(ht).add(n(tva));
        return new Operation(UUID.randomUUID(), LocalDate.of(2026, 1, 10), piece, "Vente " + piece, "VT", "Client A", n(tva), BigDecimal.ZERO, ttc, piece, null);
    }

    /** Achat : D charge HT / D TVA récupérable / C fournisseur TTC. */
    private Operation achat(String piece, String ht, String tva) {
        BigDecimal ttc = n(ht).add(n(tva));
        return new Operation(UUID.randomUUID(), LocalDate.of(2026, 1, 12), piece, "Achat " + piece, "AC", "Fournisseur B", BigDecimal.ZERO, n(tva), ttc, piece, null);
    }

    private Computation calculer(List<Operation> ops, Map<UUID, Decision> decisions, List<Adjustment> ajustements, boolean verifie) {
        return moteur.calculer(ops, decisions, ajustements, CADRE, verifie, null, null);
    }

    private Line ligne(Computation c, Operation op) {
        return c.lines().stream().filter(l -> l.ecritureId().equals(op.ecritureId())).findFirst().orElseThrow();
    }

    @Test
    void tvaCollectee_baseEtTauxDuCadre_resultatsBigDecimal() {
        Operation v = vente("F1", "1000", "100");
        Computation c = calculer(List.of(v), Map.of(), List.of(), true);
        Line l = ligne(c, v);
        assertThat(l.side()).isEqualTo("OUTPUT");
        assertThat(l.treatment()).isEqualTo(TaxTreatment.OUTPUT_TAX);
        assertThat(l.base()).isEqualByComparingTo("1000");
        assertThat(l.rateCode()).isEqualTo("T10");
        assertThat(l.vatFiscal()).isEqualByComparingTo("100.00");
        assertThat(l.status()).isEqualTo("OK");
        assertThat(c.totals().outputBase()).isEqualByComparingTo("1000");
        assertThat(c.totals().outputVatFiscal()).isEqualByComparingTo("100");
        assertThat(c.totals().netDeclared()).isEqualByComparingTo("100");
        assertThat(c.basis()).isEqualTo("FISCAL_VERIFIED");
        assertThat(c.anomalies()).isEmpty();
    }

    @Test
    void tvaDeductible_distinguee_de_la_tvaComptabilisee() {
        Operation a1 = achat("A1", "2000", "200");
        Operation a2 = achat("A2", "1000", "50");
        Operation a3 = achat("A3", "400", "40");
        Map<UUID, Decision> decisions = Map.of(a2.ecritureId(), new Decision(TaxTreatment.NON_DEDUCTIBLE, null, "Dépense non déductible justifiée"),
                a3.ecritureId(), new Decision(TaxTreatment.PARTIALLY_DEDUCTIBLE, n("50"), "Usage mixte justifié"));
        Computation c = calculer(List.of(a1, a2, a3), decisions, List.of(), true);
        assertThat(ligne(c, a1).vatDeductible()).isEqualByComparingTo("200");
        assertThat(ligne(c, a2).vatDeductible()).isEqualByComparingTo("0");              // comptabilisée : 50 ; déductible : 0
        assertThat(ligne(c, a2).vatAccounted()).isEqualByComparingTo("50");
        assertThat(ligne(c, a3).vatDeductible()).isEqualByComparingTo("20.00");           // 50 % de 40
        assertThat(c.totals().inputVatAccounted()).isEqualByComparingTo("290");
        assertThat(c.totals().inputVatDeductible()).isEqualByComparingTo("220");
        assertThat(c.totals().netDeclared()).isEqualByComparingTo("-220");                // crédit de TVA
    }

    @Test
    void sansCadreVerifie_aucunMontantFiscal_montantsComptabilisesMarquesNonVerifies() {
        Operation v = vente("F1", "1000", "100");
        Computation c = calculer(List.of(v), Map.of(), List.of(), false);
        assertThat(ligne(c, v).vatFiscal()).isNull();
        assertThat(ligne(c, v).rateCode()).isNull();
        assertThat(c.totals().outputVatFiscal()).isNull();
        assertThat(c.totals().fiscalComplete()).isFalse();
        assertThat(c.basis()).isEqualTo("ACCOUNTED_UNVERIFIED");
        assertThat(c.totals().outputVatAccounted()).isEqualByComparingTo("100");
        assertThat(c.anomalies()).anySatisfy(a -> {
            assertThat(a.code()).isEqualTo("TAX_FRAMEWORK_NOT_VERIFIED");
            assertThat(a.key()).isEqualTo("PACK");
            assertThat(a.bloquePreparation()).isFalse();                                  // n'empêche pas la préparation, empêche la finalisation
        });
    }

    @Test
    void sansAucunCadre_memeComportement() {
        Operation v = vente("F1", "1000", "100");
        Computation c = moteur.calculer(List.of(v), Map.of(), List.of(), null, false, null, null);
        assertThat(c.totals().outputVatFiscal()).isNull();
        assertThat(c.basis()).isEqualTo("ACCOUNTED_UNVERIFIED");
        assertThat(c.officialStructure()).isFalse();                                       // structure générique, jamais présentée comme officielle
    }

    @Test
    void operationSurLesDeuxComptes_estToReview_jamaisCalculeeSurHypothese() {
        Operation mixte = new Operation(UUID.randomUUID(), LocalDate.of(2026, 1, 15), "M1", "Mixte", "OD", null, n("100"), n("50"), n("2000"), "M1", null);
        Computation c = calculer(List.of(mixte), Map.of(), List.of(), true);
        Line l = ligne(c, mixte);
        assertThat(l.treatment()).isEqualTo(TaxTreatment.TO_REVIEW);
        assertThat(l.status()).isEqualTo("TO_REVIEW");
        assertThat(l.vatFiscal()).isNull();
        assertThat(l.issues()).contains(VatEngine.TAX_TREATMENT_MISSING);
        assertThat(c.totals().toReview()).isEqualTo(1);
        assertThat(c.totals().outputBase()).isEqualByComparingTo("0");                     // exclue des totaux tant qu'elle n'est pas décidée
    }

    @Test
    void decisionHumaine_leve_leToReview_et_sertDeTraitement() {
        Operation mixte = new Operation(UUID.randomUUID(), LocalDate.of(2026, 1, 15), "M1", "Mixte", "OD", null, n("100"), BigDecimal.ZERO.add(n("0")), n("1100"), "M1", null);
        // un côté nul : l'opération est déterminable ; la décision EXEMPT est un choix humain justifié
        Computation c = calculer(List.of(mixte), Map.of(mixte.ecritureId(), new Decision(TaxTreatment.EXEMPT, null, "Opération exonérée selon le dossier")), List.of(), true);
        Line l = ligne(c, mixte);
        assertThat(l.treatmentSource()).isEqualTo("DECISION");
        assertThat(l.vatFiscal()).isEqualByComparingTo("0");
        assertThat(l.explication()).isEqualTo("DECISION");
        assertThat(c.totals().toReview()).isZero();
        assertThat(c.totals().unexplained()).isEqualByComparingTo("0");
    }

    @Test
    void tauxInconnuDuCadre_estUnConstat_pasUneApproximation() {
        Operation v = vente("F1", "1000", "180");                                          // 18 % : aucun taux du cadre de test
        Computation c = calculer(List.of(v), Map.of(), List.of(), true);
        Line l = ligne(c, v);
        assertThat(l.issues()).contains(VatEngine.TAX_RATE_UNRESOLVED);
        assertThat(l.vatFiscal()).isNull();
        assertThat(c.totals().toReview()).isEqualTo(1);
        assertThat(c.totals().fiscalComplete()).isFalse();
        assertThat(c.basis()).isEqualTo("ACCOUNTED_UNVERIFIED");
    }

    @Test
    void ecartDeMontant_hors_tolerance_estUnConstat_puisExpliquePar_unAjustement() {
        Operation v = vente("F1", "1000", "103");                                          // taux implicite 10,3 % : proche de 10 (≤ 0,5 pt), écart de 3 > tolérance (1)
        Computation c = calculer(List.of(v), Map.of(), List.of(), true);
        assertThat(ligne(c, v).issues()).contains(VatEngine.VAT_AMOUNT_MISMATCH);
        assertThat(ligne(c, v).ecart()).isEqualByComparingTo("3.00");
        assertThat(c.totals().unexplained()).isEqualByComparingTo("3");

        Adjustment justifie = new Adjustment(UUID.randomUUID(), "CORRECTION", "OUTPUT", n("3"), v.ecritureId());
        Computation expliquee = calculer(List.of(v), Map.of(), List.of(justifie), true);
        assertThat(ligne(expliquee, v).explication()).isEqualTo("ADJUSTMENT");
        assertThat(ligne(expliquee, v).issues()).isEmpty();
        assertThat(expliquee.totals().unexplained()).isEqualByComparingTo("0");
        assertThat(expliquee.totals().adjustmentsOutput()).isEqualByComparingTo("3");
        assertThat(expliquee.totals().declaredOutput()).isEqualByComparingTo("103.00");     // 100 calculés + 3 ajustés = TVA comptabilisée
    }

    @Test
    void arrondi_dansLaTolerance_nEstPasUneAnomalie() {
        Operation v = vente("F1", "333", "33");                                            // 10 % de 333 = 33,30 ; écart 0,30 ≤ tolérance
        Computation c = calculer(List.of(v), Map.of(), List.of(), true);
        assertThat(ligne(c, v).explication()).isEqualTo("ROUNDING");
        assertThat(c.anomalies()).isEmpty();
    }

    @Test
    void avoir_montantsNegatifs_et_baseDeFacture_et_doublon() {
        Operation avoir = new Operation(UUID.randomUUID(), LocalDate.of(2026, 1, 20), "AV1", "Avoir", "VT", "Client A", n("-50"), BigDecimal.ZERO, n("550"), "AV1", null);
        Computation c = calculer(List.of(avoir), Map.of(), List.of(), true);
        assertThat(ligne(c, avoir).base()).isEqualByComparingTo("-500");
        assertThat(ligne(c, avoir).vatFiscal()).isEqualByComparingTo("-50.00");
        assertThat(c.totals().outputVatFiscal()).isEqualByComparingTo("-50");

        Operation facture = new Operation(UUID.randomUUID(), LocalDate.of(2026, 1, 21), "F9", "Vente", "VT", "Client A", n("100"), BigDecimal.ZERO, n("1100"), "F9", n("900"));
        assertThat(ligne(calculer(List.of(facture), Map.of(), List.of(), true), facture).issues()).contains(VatEngine.VAT_BASE_MISMATCH);

        Operation d1 = vente("F7", "1000", "100");
        Operation d2 = vente("F7", "1000", "100");
        Computation doublons = calculer(List.of(d1, d2), Map.of(), List.of(), true);
        assertThat(ligne(doublons, d1).issues()).doesNotContain(VatEngine.DUPLICATE_TAX_DOCUMENT);
        assertThat(ligne(doublons, d2).issues()).contains(VatEngine.DUPLICATE_TAX_DOCUMENT);
    }

    @Test
    void reconciliation_grandLivre_et_operations() {
        Operation v = vente("F1", "1000", "100");
        Computation ok = moteur.calculer(List.of(v), Map.of(), List.of(), CADRE, true, n("100"), n("0"));
        assertThat(ok.reconciliation().ledgerGap()).isEqualByComparingTo("0");
        assertThat(ok.anomalies()).isEmpty();
        Computation ecart = moteur.calculer(List.of(v), Map.of(), List.of(), CADRE, true, n("160"), n("0"));
        assertThat(ecart.reconciliation().ledgerGap()).isEqualByComparingTo("60");
        assertThat(ecart.anomalies()).anySatisfy(a -> {
            assertThat(a.code()).isEqualTo("TAX_PERIOD_INCOMPLETE");
            assertThat(a.key()).isEqualTo("LEDGER");
            assertThat(a.bloquePreparation()).isTrue();
        });
    }

    @Test
    void declaration_structureGeneriqueOuDuCadre_codesOfficielsSeulementSiLeCadreLesFournit() {
        Operation v = vente("F1", "1000", "100");
        Computation generique = calculer(List.of(v), Map.of(), List.of(), true);
        assertThat(generique.officialStructure()).isFalse();
        assertThat(generique.declaration()).extracting(VatModel.DeclarationLine::source).contains("OUTPUT_BASE", "OUTPUT_VAT", "NET_VAT");

        VatRules avecCases = new VatRules(CADRE.rates(), "MONTHLY", null, n("1"), n("0.5"), List.of(new VatRules.DeclarationLine("X01", "Case fictive 1", "OUTPUT_BASE"),
                new VatRules.DeclarationLine("X02", "Case fictive 2", "NET_VAT")));
        Computation officielle = moteur.calculer(List.of(v), Map.of(), List.of(), avecCases, true, null, null);
        assertThat(officielle.officialStructure()).isTrue();
        assertThat(officielle.declaration()).extracting(VatModel.DeclarationLine::code).containsExactly("X01", "X02");
        assertThat(officielle.declaration().get(0).amount()).isEqualByComparingTo("1000");
    }

    @Test
    void recalculIdentique_memeResultat() {
        List<Operation> ops = List.of(vente("F1", "1000", "100"), achat("A1", "400", "40"), vente("F2", "500", "50"));
        Computation a = calculer(ops, Map.of(), List.of(), true);
        Computation b = calculer(ops, Map.of(), List.of(), true);
        assertThat(a.totals()).isEqualTo(b.totals());
        assertThat(a.lines()).isEqualTo(b.lines());
        assertThat(a.anomalies()).isEqualTo(b.anomalies());
    }
}
