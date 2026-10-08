package com.example.comptable.etatsfinanciers;

import com.example.comptable.etatsfinanciers.definition.AccountMappingRule;
import com.example.comptable.etatsfinanciers.definition.BalanceSide;
import com.example.comptable.etatsfinanciers.definition.CalculationType;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.FormulaEvaluator;
import com.example.comptable.etatsfinanciers.definition.MappingRole;
import com.example.comptable.etatsfinanciers.definition.PresentationSystem;
import com.example.comptable.etatsfinanciers.definition.ProvenanceType;
import com.example.comptable.etatsfinanciers.definition.SignConvention;
import com.example.comptable.etatsfinanciers.definition.SourceReference;
import com.example.comptable.etatsfinanciers.definition.StatementLineDefinition;
import com.example.comptable.etatsfinanciers.definition.StatementScope;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.definition.VerificationStatus;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.AccountInput;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.MappingOutcome;
import com.example.comptable.etatsfinanciers.service.NotesCatalogueService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Definitions reglementaires versionnees (F2), resolveur unique (F3), formules du compte de resultat et du TFT. Tests sans base : le contenu des definitions est
 * verifie contre le paquet reglementaire (docs/syscohada-official-matrix.md), les calculs contre des valeurs attendues faites a la main.
 */
class FinancialStatementDefinitionTest {

    private final FinancialStatementDefinitionRegistry registry = new FinancialStatementDefinitionRegistry();
    private final FinancialStatementMappingResolver resolver = new FinancialStatementMappingResolver();

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private AccountInput compte(String numero, String net) {
        return new AccountInput(UUID.randomUUID(), numero, "Compte " + numero, bd(net));
    }

    // ------------------------------------------------------------------ definitions

    @Test
    void lesDefinitionsPortentCadreVersionSourceEtEmpreinte() {
        for (StatementType type : List.of(StatementType.COMPTE_RESULTAT, StatementType.BILAN_ACTIF, StatementType.BILAN_PASSIF, StatementType.TFT)) {
            FinancialStatementDefinition d = registry.courante(type);
            assertThat(d.framework()).isEqualTo("SYSCOHADA_REVISE_AUDCIF_2017");
            assertThat(d.version()).isEqualTo("2");
            assertThat(d.presentationSystem()).isEqualTo(PresentationSystem.NORMAL);
            assertThat(d.effectiveFrom()).isEqualTo(LocalDate.of(2018, 1, 1));
            assertThat(d.source().sha256()).isEqualTo("d5ecb380984b792c4d586b0efa77931249a4c0c25daf39d3c50b27648dd05300");
            assertThat(d.contentHash()).hasSize(64);
            assertThat(d.lines()).isNotEmpty();
        }
        assertThat(registry.courante(StatementType.COMPTE_RESULTAT).contentHash()).isNotEqualTo(registry.courante(StatementType.TFT).contentHash());
    }

    @Test
    void jamaisDeRegleSurUneDonneeNonVerifiee_ni_corroboreeSeule() {
        for (StatementType type : List.of(StatementType.COMPTE_RESULTAT, StatementType.BILAN_ACTIF, StatementType.BILAN_PASSIF, StatementType.TFT)) {
            for (StatementLineDefinition ligne : registry.courante(type).lines()) {
                for (AccountMappingRule regle : ligne.mappingRules()) {
                    assertThat(regle.verificationStatus()).as("%s/%s", type, ligne.code()).isEqualTo(VerificationStatus.VERIFIED);
                    assertThat(regle.provenanceType()).as("%s/%s", type, ligne.code()).isNotEqualTo(ProvenanceType.CORROBORATED_ONLY);
                }
            }
        }
    }

    @Test
    void compteDeResultat_42Lignes_toutesVerifiees() {
        FinancialStatementDefinition cr = registry.courante(StatementType.COMPTE_RESULTAT);
        assertThat(cr.lines()).hasSize(42);
        assertThat(cr.lines().stream().filter(l -> l.calculationType() == CalculationType.MAPPED)).hasSize(33);
        assertThat(cr.lines().stream().filter(l -> l.calculationType() == CalculationType.FORMULA)).hasSize(9);
        assertThat(cr.lines()).allSatisfy(l -> {
            assertThat(l.verificationStatus()).as(l.code()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(l.provenanceType()).as(l.code()).isEqualTo(ProvenanceType.DIRECT_PRIMARY);
        });
        assertThat(cr.ligne("XA").orElseThrow().formula()).isEqualTo("TA + RA + RB");     // p.1069 : MARGE COMMERCIALE (Somme TA a RB)
        // TG = +71 (incertitude levee)
        assertThat(cr.ligne("TG").orElseThrow().mappingRules().get(0).includePrefixes()).containsExactly("71");
        assertThat(cr.ligne("RK").orElseThrow().mappingRules().get(0).includePrefixes()).containsExactly("66");   // 66 = charges de personnel
        assertThat(cr.ligne("RM").orElseThrow().mappingRules().get(0).includePrefixes()).containsExactly("67");   // 67 = frais financiers
    }

    @Test
    void passif_22PostesMappesDerives_etActif_seulementLesReglesVerifiees() {
        FinancialStatementDefinition passif = registry.courante(StatementType.BILAN_PASSIF);
        assertThat(passif.lines()).hasSize(28);
        Map<String, List<String>> attendu = new java.util.LinkedHashMap<>();
        attendu.put("CA", List.of("101", "102")); attendu.put("CB", List.of("109")); attendu.put("CD", List.of("105")); attendu.put("CE", List.of("106"));
        attendu.put("CF", List.of("111", "112", "113")); attendu.put("CG", List.of("118")); attendu.put("CH", List.of("12")); attendu.put("CL", List.of("14")); attendu.put("CM", List.of("15"));
        attendu.put("DA", List.of("16", "181", "182", "183", "184")); attendu.put("DB", List.of("17")); attendu.put("DC", List.of("19"));
        attendu.put("DH", List.of("481", "482", "484")); attendu.put("DI", List.of("419")); attendu.put("DJ", List.of("401", "402", "408"));
        attendu.put("DK", List.of("42", "43", "44")); attendu.put("DM", List.of("185", "45", "46", "47")); attendu.put("DN", List.of("499"));
        attendu.put("DQ", List.of("564", "565")); attendu.put("DV", List.of("479"));
        attendu.forEach((code, prefixes) -> {
            StatementLineDefinition l = passif.ligne(code).orElseThrow();
            assertThat(l.calculationType()).as(code).isEqualTo(CalculationType.MAPPED);
            assertThat(l.verificationStatus()).as(code).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(l.provenanceType()).as(code).isEqualTo(ProvenanceType.DERIVED_FROM_PRIMARY);
            assertThat(l.mappingRules().get(0).includePrefixes()).as(code).containsExactlyElementsOf(prefixes);
        });
        assertThat(passif.ligne("DR").orElseThrow().mappingRules()).hasSize(2);
        assertThat(passif.ligne("CJ").orElseThrow().calculationType()).isEqualTo(CalculationType.RESULT_IDENTITY);
        assertThat(passif.ligne("DK").orElseThrow().mappingRules().get(0).side()).isEqualTo(BalanceSide.CREDIT);
        assertThat(passif.ligne("DM").orElseThrow().mappingRules().get(0).excludePrefixes()).containsExactly("479");
        assertThat(passif.ligne("DN").orElseThrow().mappingRules().get(0).excludePrefixes()).containsExactly("4998");
        assertThat(passif.ligne("CP").orElseThrow().formula()).isEqualTo("CA - CB + CD + CE + CF + CG + CH + CJ + CL + CM");
        assertThat(passif.ligne("DD").orElseThrow().formula()).isEqualTo("DA + DB + DC");
        assertThat(passif.ligne("DF").orElseThrow().formula()).isEqualTo("CP + DD");
        assertThat(passif.ligne("DP").orElseThrow().formula()).isEqualTo("DH + DI + DJ + DK + DM + DN");
        assertThat(passif.ligne("DT").orElseThrow().formula()).isEqualTo("DQ + DR");
        assertThat(passif.ligne("DZ").orElseThrow().formula()).isEqualTo("DF + DP + DT + DV");

        FinancialStatementDefinition actif = registry.courante(StatementType.BILAN_ACTIF);
        assertThat(actif.lines()).hasSize(29);
        assertThat(actif.ligne("AD").orElseThrow().formula()).isEqualTo("AE + AF + AG + AH");
        assertThat(actif.ligne("AI").orElseThrow().formula()).isEqualTo("AJ + AK + AL + AM + AN");
        assertThat(actif.ligne("AQ").orElseThrow().formula()).isEqualTo("AR + AS");
        assertThat(actif.ligne("AZ").orElseThrow().formula()).isEqualTo("AD + AI + AP + AQ");
        assertThat(actif.ligne("BG").orElseThrow().formula()).isEqualTo("BH + BI + BJ");
        assertThat(actif.ligne("BK").orElseThrow().formula()).isEqualTo("BA + BB + BG");
        assertThat(actif.ligne("BT").orElseThrow().formula()).isEqualTo("BQ + BR + BS");
        assertThat(actif.ligne("BZ").orElseThrow().formula()).isEqualTo("AZ + BK + BT + BU");
        for (String code : List.of("AK", "AL")) {
            assertThat(actif.ligne(code).orElseThrow().calculationType()).as(code).isEqualTo(CalculationType.STRUCTURE_ONLY);
            assertThat(actif.ligne(code).orElseThrow().verificationStatus()).as(code).isEqualTo(VerificationStatus.PARTIAL);
        }
        for (String code : List.of("AE", "AF", "AH", "AM", "AN")) {
            StatementLineDefinition l = actif.ligne(code).orElseThrow();
            assertThat(l.verificationStatus()).as(code).isEqualTo(VerificationStatus.PARTIAL);
            assertThat(l.mappingRules()).as(code).hasSize(1);
            assertThat(l.mappingRules().get(0).role()).as(code).isEqualTo(MappingRole.GROSS);
        }
        List<String> mappees = actif.lines().stream().filter(l -> l.calculationType() == CalculationType.MAPPED).map(StatementLineDefinition::code).toList();
        assertThat(mappees).containsExactlyInAnyOrder("AE", "AF", "AG", "AH", "AJ", "AM", "AN", "AP", "AR", "AS", "BA", "BB", "BH", "BI", "BJ", "BQ", "BR", "BS", "BU");
        // G0 : BH = brut 409, depreciation 490 (jamais 499)
        StatementLineDefinition bh = actif.ligne("BH").orElseThrow();
        assertThat(bh.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(bh.mappingRules().stream().filter(r -> r.role() == MappingRole.GROSS).findFirst().orElseThrow().includePrefixes()).containsExactly("409");
        assertThat(bh.mappingRules().stream().filter(r -> r.role() == MappingRole.DEPRECIATION).findFirst().orElseThrow().includePrefixes()).containsExactly("490");
        assertThat(actif.lines().stream().flatMap(l -> l.mappingRules().stream()).filter(r -> r.role() == MappingRole.DEPRECIATION).flatMap(r -> r.includePrefixes().stream()))
                .doesNotContain("499");
        StatementLineDefinition bj = actif.ligne("BJ").orElseThrow();
        assertThat(bj.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        var bjBrut = bj.mappingRules().stream().filter(r -> r.role() == MappingRole.GROSS).findFirst().orElseThrow();
        assertThat(bjBrut.includePrefixes()).containsExactly("185", "42", "43", "44", "45", "46", "47");
        assertThat(bjBrut.excludePrefixes()).containsExactly("478");
        assertThat(bjBrut.side()).isEqualTo(BalanceSide.DEBIT);
        assertThat(bj.mappingRules().stream().filter(r -> r.role() == MappingRole.DEPRECIATION).findFirst().orElseThrow().includePrefixes())
                .containsExactly("492", "493", "494", "495", "496", "497");
        StatementLineDefinition bs = actif.ligne("BS").orElseThrow();
        assertThat(bs.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        var bsBrut = bs.mappingRules().stream().filter(r -> r.role() == MappingRole.GROSS).findFirst().orElseThrow();
        assertThat(bsBrut.includePrefixes()).containsExactly("52", "53", "54", "55", "57", "581", "582");
        assertThat(bsBrut.side()).isEqualTo(BalanceSide.DEBIT);
        assertThat(bs.mappingRules().stream().filter(r -> r.role() == MappingRole.DEPRECIATION).findFirst().orElseThrow().includePrefixes()).containsExactly("592", "593", "594");
        assertThat(actif.ligne("BU").orElseThrow().mappingRules().get(0).includePrefixes()).containsExactly("478");
        assertThat(actif.ligne("BI").orElseThrow().mappingRules().get(0).excludePrefixes()).containsExactly("419");
    }

    @Test
    void comptesReservesPartial_neSontMappesNiALActifNiAuPassif() {
        FinancialStatementDefinition actif = registry.courante(StatementType.BILAN_ACTIF);
        FinancialStatementDefinition passif = registry.courante(StatementType.BILAN_PASSIF);
        for (String numero : List.of("103", "104", "404", "186", "187", "188", "4998", "599")) {
            AccountInput c = compte(numero, "-100");
            assertThat(resolver.resoudre(actif, List.of(c)).mapped()).as("actif %s", numero).isEmpty();
            assertThat(resolver.resoudre(passif, List.of(c)).mapped()).as("passif %s", numero).isEmpty();
        }
        assertThat(resolver.resoudre(actif, List.of(compte("490", "-5"))).mapped()).extracting(x -> x.lineCode()).containsExactly("BH");
        assertThat(resolver.resoudre(actif, List.of(compte("499", "-5"))).mapped()).isEmpty();
        assertThat(resolver.resoudre(passif, List.of(compte("499", "-5"))).mapped()).extracting(x -> x.lineCode()).containsExactly("DN");
        for (String numero : List.of("492", "493", "494", "495", "496", "497")) {
            assertThat(resolver.resoudre(actif, List.of(compte(numero, "-5"))).mapped()).extracting(x -> x.lineCode()).containsExactly("BJ");
        }
        for (String numero : List.of("592", "593", "594")) {
            assertThat(resolver.resoudre(actif, List.of(compte(numero, "-5"))).mapped()).extracting(x -> x.lineCode()).containsExactly("BS");
        }
    }

    @Test
    void totauxDuBilan_calculesParLesFormulesDesDefinitions_etBZegalDZ() {
        FinancialStatementDefinition actif = registry.courante(StatementType.BILAN_ACTIF);
        Map<String, BigDecimal> a = new HashMap<>();
        actif.lines().stream().filter(l -> l.calculationType() != CalculationType.FORMULA).forEach(l -> a.put(l.code(), BigDecimal.ZERO));
        a.put("AJ", bd("300")); a.put("AG", bd("20")); a.put("AR", bd("40")); a.put("AS", bd("10")); a.put("BB", bd("70")); a.put("BI", bd("90")); a.put("BJ", bd("15"));
        a.put("BQ", bd("5")); a.put("BS", bd("250")); a.put("BU", bd("5"));
        Map<String, BigDecimal> va = FormulaEvaluator.evaluerDefinition(actif, a);
        assertThat(va.get("AD")).isEqualByComparingTo("20");
        assertThat(va.get("AI")).isEqualByComparingTo("300");
        assertThat(va.get("AQ")).isEqualByComparingTo("50");
        assertThat(va.get("AZ")).isEqualByComparingTo("370");
        assertThat(va.get("BG")).isEqualByComparingTo("105");
        assertThat(va.get("BK")).isEqualByComparingTo("175");
        assertThat(va.get("BT")).isEqualByComparingTo("255");
        assertThat(va.get("BZ")).isEqualByComparingTo("805");

        FinancialStatementDefinition passif = registry.courante(StatementType.BILAN_PASSIF);
        Map<String, BigDecimal> p = new HashMap<>();
        passif.lines().stream().filter(l -> l.calculationType() != CalculationType.FORMULA).forEach(l -> p.put(l.code(), BigDecimal.ZERO));
        p.put("CA", bd("500")); p.put("CB", bd("50")); p.put("CH", bd("40")); p.put("CJ", bd("60")); p.put("DA", bd("100")); p.put("DJ", bd("80")); p.put("DK", bd("20")); p.put("DR", bd("55"));
        Map<String, BigDecimal> vp = FormulaEvaluator.evaluerDefinition(passif, p);
        assertThat(vp.get("CP")).isEqualByComparingTo("550");
        assertThat(vp.get("DD")).isEqualByComparingTo("100");
        assertThat(vp.get("DF")).isEqualByComparingTo("650");
        assertThat(vp.get("DP")).isEqualByComparingTo("100");
        assertThat(vp.get("DT")).isEqualByComparingTo("55");
        assertThat(vp.get("DZ")).isEqualByComparingTo("805");
        assertThat(va.get("BZ")).isEqualByComparingTo(vp.get("DZ"));
    }

    @Test
    void tft_structureEtFormulesDeSomme() {
        FinancialStatementDefinition tft = registry.courante(StatementType.TFT);
        assertThat(tft.lines()).hasSize(25);
        assertThat(tft.ligne("ZB").orElseThrow().formula()).isEqualTo("FA + FB + FC + FD + FE");
        assertThat(tft.ligne("ZF").orElseThrow().formula()).isEqualTo("ZD + ZE");
        assertThat(tft.ligne("ZG").orElseThrow().formula()).isEqualTo("ZB + ZC + ZF");
        assertThat(tft.lines().stream().filter(l -> l.mappingRules().size() > 0)).isEmpty();   // aucune table comptes -> lignes dans la source
    }

    // ------------------------------------------------------------------ catalogue des notes (R4)

    @Test
    void catalogueDesNotes_R4_46NotesNonDecidees_etNoteNonApplicableNEstPasJointe() {
        var catalogue = registry.notes();
        assertThat(catalogue.notes()).hasSize(46);
        assertThat(catalogue.notes()).extracting(n -> n.code()).contains("3A", "3C", "6", "7", "11", "13", "16B bis", "17", "20", "21", "22", "27A", "29", "36");
        assertThat(catalogue.notes()).allSatisfy(n -> {
            assertThat(n.automationType()).isIn("AUTOMATIC", "MIXED", "MANUAL_REQUIRED");   // proposition produit, distincte de la verification et de la provenance
            assertThat(n.automationTypeBasis()).isEqualTo("PRODUCT_PROPOSAL");
            assertThat(n.applicability()).isEqualTo("TO_DETERMINE");
            assertThat(n.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        });
        NotesCatalogueService service = new NotesCatalogueService(registry);
        var selection = service.selectionner(Map.of("6", "APPLICABLE", "7", "NOT_APPLICABLE"));
        assertThat(selection.aJoindre()).extracting(n -> n.code()).containsExactly("6");
        assertThat(selection.aDecider()).extracting(n -> n.code()).doesNotContain("6", "7").hasSize(44);
        var note35 = catalogue.notes().stream().filter(n -> n.code().equals("35")).findFirst().orElseThrow();
        assertThat(note35.applicabilityRule().type()).isEqualTo("MIN_HEADCOUNT_EXCLUSIVE");
        assertThat(note35.applicabilityRule().threshold()).isEqualTo(250);   // la note 7 non applicable n'est ni jointe ni a decider
    }

    // ------------------------------------------------------------------ resolveur unique

    @Test
    void resolveur_66EstPersonnel_67EstFinancier_71EstTG_etLaClasse8EstPriseEnCompte() {
        FinancialStatementDefinition cr = registry.courante(StatementType.COMPTE_RESULTAT);
        List<AccountInput> comptes = List.of(compte("661100", "100"), compte("6711", "10"), compte("7011", "-5"), compte("7133", "-5"), compte("82", "-1"), compte("83", "1"),
                compte("84", "-1"), compte("81", "1"), compte("87", "1"), compte("89", "1"));
        MappingOutcome m = resolver.resoudre(cr, comptes);
        Map<String, String> ligneParCompte = m.mapped().stream().collect(Collectors.toMap(x -> x.account().number(), x -> x.lineCode()));
        assertThat(ligneParCompte).containsEntry("661100", "RK").containsEntry("6711", "RM").containsEntry("7011", "TA").containsEntry("7133", "TG")
                .containsEntry("82", "TN").containsEntry("83", "RP").containsEntry("84", "TO").containsEntry("81", "RO").containsEntry("87", "RQ").containsEntry("89", "RS");
        assertThat(m.unmapped()).isEmpty();
        assertThat(m.ambiguous()).isEmpty();
    }

    @Test
    void compteNonMappe_estExposeJamaisRangeDansLaPremiereRubriquePlausible() {
        FinancialStatementDefinition cr = registry.courante(StatementType.COMPTE_RESULTAT);
        MappingOutcome m = resolver.resoudre(cr, List.of(compte("7088", "-10"), compte("701", "-100"), compte("411", "50")));
        assertThat(m.unmapped()).extracting(AccountInput::number).containsExactly("7088");          // 708 n'est dans aucune regle verifiee
        assertThat(m.mapped()).extracting(x -> x.account().number()).containsExactly("701");
        // un compte de classe 4 n'est pas pertinent pour le compte de resultat
        assertThat(m.unmapped()).extracting(AccountInput::number).doesNotContain("411");
    }

    @Test
    void actif_filtreDeCote_etExclusions() {
        FinancialStatementDefinition actif = registry.courante(StatementType.BILAN_ACTIF);
        MappingOutcome m = resolver.resoudre(actif, List.of(compte("4111", "500"), compte("419", "-50"), compte("421", "30"), compte("422", "-30"), compte("521", "700"), compte("401", "-80")));
        Map<String, String> ligneParCompte = m.mapped().stream().collect(Collectors.toMap(x -> x.account().number(), x -> x.lineCode()));
        assertThat(ligneParCompte).containsEntry("4111", "BI").containsEntry("421", "BJ").containsEntry("521", "BS");
        // 419 (clients crediteurs), 422 a solde crediteur et 401 (fournisseurs) : non mappes (Passif bloque)
        assertThat(m.unmapped()).extracting(AccountInput::number).containsExactlyInAnyOrder("419", "422", "401");
    }

    @Test
    void compte478_vaSurBUSeulement_etPasSurBJ() {
        MappingOutcome m = resolver.resoudre(registry.courante(StatementType.BILAN_ACTIF), List.of(compte("478", "40"), compte("4711", "10")));
        Map<String, String> ligneParCompte = m.mapped().stream().collect(Collectors.toMap(x -> x.account().number(), x -> x.lineCode()));
        assertThat(ligneParCompte).containsEntry("478", "BU").containsEntry("4711", "BJ");
        assertThat(m.ambiguous()).isEmpty();
    }

    @Test
    void terrains_AJ_brut22_amortissementsEtDepreciations282et292() {
        MappingOutcome m = resolver.resoudre(registry.courante(StatementType.BILAN_ACTIF), List.of(compte("2211", "500"), compte("2824", "-50"), compte("2921", "-20")));
        assertThat(m.mapped()).extracting(x -> x.lineCode()).containsOnly("AJ");
        assertThat(m.mapped().stream().filter(x -> x.role() == MappingRole.DEPRECIATION)).extracting(x -> x.account().number()).containsExactlyInAnyOrder("2824", "2921");
    }

    @Test
    void definitionAvecChevauchement_estRefuseeALaValidation() {
        AccountMappingRule r1 = new AccountMappingRule(MappingRole.GROSS, List.of("70"), List.of(), BalanceSide.ANY, null, VerificationStatus.VERIFIED, ProvenanceType.DIRECT_PRIMARY);
        AccountMappingRule r2 = new AccountMappingRule(MappingRole.GROSS, List.of("701"), List.of(), BalanceSide.ANY, null, VerificationStatus.VERIFIED, ProvenanceType.DIRECT_PRIMARY);
        FinancialStatementDefinition d = definition(List.of(ligne("AA", 1, r1), ligne("BB", 2, r2)));
        assertThatThrownBy(() -> FinancialStatementDefinitionRegistry.valider(d)).hasMessageContaining("chevauchement");
    }

    @Test
    void regleNonVerifiee_ou_corroboreeSeule_estRefuseeALaValidation() {
        AccountMappingRule partielle = new AccountMappingRule(MappingRole.GROSS, List.of("701"), List.of(), BalanceSide.ANY, null, VerificationStatus.PARTIAL, ProvenanceType.DIRECT_PRIMARY);
        assertThatThrownBy(() -> FinancialStatementDefinitionRegistry.valider(definition(List.of(ligne("AA", 1, partielle))))).hasMessageContaining("VERIFIED");
        AccountMappingRule corroboree = new AccountMappingRule(MappingRole.GROSS, List.of("701"), List.of(), BalanceSide.ANY, null, VerificationStatus.VERIFIED, ProvenanceType.CORROBORATED_ONLY);
        assertThatThrownBy(() -> FinancialStatementDefinitionRegistry.valider(definition(List.of(ligne("AA", 1, corroboree))))).hasMessageContaining("corroboration");
    }

    @Test
    void compteAcceptePlusieursLignes_estAmbigu_etExcluDesMontants() {
        // definition de test dont deux lignes se recouvrent par le cote : la validation est contournee volontairement pour tester le resolveur
        AccountMappingRule debit = new AccountMappingRule(MappingRole.GROSS, List.of("70"), List.of(), BalanceSide.DEBIT, null, VerificationStatus.VERIFIED, ProvenanceType.DIRECT_PRIMARY);
        AccountMappingRule tous = new AccountMappingRule(MappingRole.GROSS, List.of("701"), List.of(), BalanceSide.ANY, null, VerificationStatus.VERIFIED, ProvenanceType.DIRECT_PRIMARY);
        FinancialStatementDefinition d = definition(List.of(ligne("AA", 1, debit), ligne("BB", 2, tous)));
        MappingOutcome m = resolver.resoudre(d, List.of(compte("701", "10")));
        assertThat(m.ambiguous()).hasSize(1);
        assertThat(m.ambiguous().get(0).lineCodes()).containsExactlyInAnyOrder("AA", "BB");
        assertThat(m.mapped()).isEmpty();
    }

    // ------------------------------------------------------------------ formules du compte de resultat : calcul manuel attendu

    @Test
    void formulesDuCompteDeResultat_valeursAttenduesCalculeesAlaMain() {
        FinancialStatementDefinition cr = registry.courante(StatementType.COMPTE_RESULTAT);
        Map<String, BigDecimal> l = new HashMap<>();
        cr.lines().stream().filter(x -> x.calculationType() == CalculationType.MAPPED).forEach(x -> l.put(x.code(), BigDecimal.ZERO));
        // Montants algebriques (credit - debit) : produits positifs, charges negatives
        l.put("TA", bd("1000000")); l.put("RA", bd("-400000")); l.put("RB", bd("-20000"));
        l.put("TB", bd("100000")); l.put("TC", bd("50000")); l.put("TD", bd("30000"));
        l.put("TE", bd("10000")); l.put("TF", bd("5000")); l.put("TG", bd("7000")); l.put("TH", bd("3000")); l.put("TI", bd("2000"));
        l.put("RC", bd("-60000")); l.put("RE", bd("-15000")); l.put("RG", bd("-8000")); l.put("RH", bd("-18000")); l.put("RI", bd("-4000")); l.put("RJ", bd("-3000"));
        l.put("RK", bd("-120000")); l.put("TJ", bd("9000")); l.put("RL", bd("-25000"));
        l.put("TK", bd("11000")); l.put("RM", bd("-14000"));
        l.put("TN", bd("40000")); l.put("RO", bd("-35000")); l.put("TO", bd("5000")); l.put("RP", bd("-2000"));
        l.put("RQ", bd("-1000")); l.put("RS", bd("-10000"));

        Map<String, BigDecimal> v = FormulaEvaluator.evaluerDefinition(cr, l);

        assertThat(v.get("XA")).isEqualByComparingTo("580000");     // marge commerciale = 1 000 000 - 400 000 - 20 000
        assertThat(v.get("XB")).isEqualByComparingTo("1180000");    // chiffre d'affaires = 1 000 000 + 100 000 + 50 000 + 30 000
        assertThat(v.get("XC")).isEqualByComparingTo("679000");     // valeur ajoutee
        assertThat(v.get("XD")).isEqualByComparingTo("559000");     // EBE
        assertThat(v.get("XE")).isEqualByComparingTo("543000");     // resultat d'exploitation
        assertThat(v.get("XF")).isEqualByComparingTo("-3000");      // resultat financier
        assertThat(v.get("XG")).isEqualByComparingTo("540000");     // RAO
        assertThat(v.get("XH")).isEqualByComparingTo("8000");       // resultat HAO
        assertThat(v.get("XI")).isEqualByComparingTo("537000");     // resultat net
    }

    // ------------------------------------------------------------------ TFT : formules officielles et invariants

    @Test
    void formulesDuTft_ZB_ZC_ZD_ZE_ZF_ZG_et_continuiteZAplusZGegalZH() {
        FinancialStatementDefinition tft = registry.courante(StatementType.TFT);
        Map<String, BigDecimal> flux = new HashMap<>();
        flux.put("ZA", bd("100000"));
        flux.put("FA", bd("500000")); flux.put("FB", bd("-10000")); flux.put("FC", bd("-20000")); flux.put("FD", bd("-30000")); flux.put("FE", bd("15000"));
        flux.put("FF", bd("-100000")); flux.put("FG", bd("-200000")); flux.put("FH", BigDecimal.ZERO); flux.put("FI", bd("50000")); flux.put("FJ", BigDecimal.ZERO);
        flux.put("FK", bd("300000")); flux.put("FL", BigDecimal.ZERO); flux.put("FM", BigDecimal.ZERO); flux.put("FN", bd("-40000"));
        flux.put("FO", bd("100000")); flux.put("FP", BigDecimal.ZERO); flux.put("FQ", bd("-60000"));
        flux.put("ZH", bd("605000"));

        Map<String, BigDecimal> v = FormulaEvaluator.evaluerDefinition(tft, flux);

        assertThat(v.get("ZB")).isEqualByComparingTo("455000");
        assertThat(v.get("ZC")).isEqualByComparingTo("-250000");
        assertThat(v.get("ZD")).isEqualByComparingTo("260000");
        assertThat(v.get("ZE")).isEqualByComparingTo("40000");
        assertThat(v.get("ZF")).isEqualByComparingTo("300000");
        assertThat(v.get("ZG")).isEqualByComparingTo("505000");
        assertThat(v.get("ZA").add(v.get("ZG"))).isEqualByComparingTo(v.get("ZH"));
    }

    @Test
    void tft_uneValeurInconnueRendLeTotalInconnu_jamaisZero() {
        FinancialStatementDefinition tft = registry.courante(StatementType.TFT);
        Map<String, BigDecimal> flux = new HashMap<>();
        flux.put("FA", bd("1"));            // FB..FE absents
        Map<String, BigDecimal> v = FormulaEvaluator.evaluerDefinition(tft, flux);
        assertThat(v.get("ZB")).isNull();
        assertThat(v.get("ZG")).isNull();
    }

    // ------------------------------------------------------------------ evaluateur

    @Test
    void evaluateur_somme_parentheses_et_signes() {
        Map<String, BigDecimal> v = Map.of("AA", bd("10"), "BB", bd("3"), "CC", bd("4"));
        assertThat(FormulaEvaluator.evaluer("AA - (BB + CC)", v::get, (a, b) -> List.of())).isEqualByComparingTo("3");
        assertThat(FormulaEvaluator.evaluer("AA - BB - CC", v::get, (a, b) -> List.of())).isEqualByComparingTo("3");
        assertThat(FormulaEvaluator.evaluer("SUM(BB..CC)", v::get, (a, b) -> List.of("BB", "CC"))).isEqualByComparingTo("7");
    }

    // ------------------------------------------------------------------ outils

    private static StatementLineDefinition ligne(String code, int ordre, AccountMappingRule regle) {
        return new StatementLineDefinition(code, code, ordre, null, null, CalculationType.MAPPED, SignConvention.RESULT_ALGEBRAIC, List.of(regle), null, null,
                VerificationStatus.VERIFIED, ProvenanceType.DIRECT_PRIMARY, "test", null, null);
    }

    private static FinancialStatementDefinition definition(List<StatementLineDefinition> lignes) {
        return new FinancialStatementDefinition("TEST", "1", StatementType.COMPTE_RESULTAT, PresentationSystem.NORMAL, LocalDate.of(2018, 1, 1),
                new SourceReference("test", "x", "x", "x", "x"), new StatementScope(List.of(7)), lignes, "hash");
    }
}
