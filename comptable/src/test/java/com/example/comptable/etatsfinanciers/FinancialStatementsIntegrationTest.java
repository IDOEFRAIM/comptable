package com.example.comptable.etatsfinanciers;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService;
import com.example.comptable.etatsfinanciers.service.CashFlowStatementService;
import com.example.comptable.etatsfinanciers.service.IncomeStatementService;
import com.example.comptable.etatsfinanciers.service.StatementControlPublisher;
import com.example.comptable.etatsfinanciers.service.StatementDrillDownService;
import com.example.comptable.etatsfinanciers.service.StatementResult;
import com.example.comptable.etatsfinanciers.service.StatementResult.Line;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshot;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotRepository;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Etats financiers SYSCOHADA sur un vrai PostgreSQL : compte de resultat complet (valeurs calculees a la main), N / N-1, HAO, 66 personnel / 67 financier, TG -> 71,
 * ecriture de cloture sans effet sur le resultat de N, comptes non mappes -> Finding, Bilan Actif partiel (INCOMPLETE), TFT (structure), drill-down, snapshot,
 * isolation multi-tenant, nombre de requetes.
 */
class FinancialStatementsIntegrationTest extends BankTestBase {

    @Autowired IncomeStatementService incomeStatementService;
    @Autowired BalanceSheetService balanceSheetService;
    @Autowired CashFlowStatementService cashFlowStatementService;
    @Autowired StatementControlPublisher controlPublisher;
    @Autowired StatementDrillDownService drillDownService;
    @Autowired FinancialStatementSnapshotService snapshotService;
    @Autowired FinancialStatementSnapshotRepository snapshotRepository;
    @Autowired EcritureComptableService ecritureService;
    @Autowired FacturationService facturationService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    // ------------------------------------------------------------------ outils

    /** Compte local « <prefixe>900 » (cree au besoin) : le numero commence par le prefixe de la regle de correspondance visee. */
    private UUID compte(Dossier d, String prefixe) {
        String numero = prefixe + "900";
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseGet(() -> {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(d.id()));
            c.setNumero(numero);
            c.setIntitule("Compte de test " + numero);
            c.setLettrable(false);
            return compteComptableRepository.save(c);
        }).getId();
    }

    private UUID journal(Dossier d) {
        return jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
    }

    /** Une ecriture validee ; {@code credits} et {@code debits} par prefixe de compte, equilibree par 521 (banque) du solde. */
    private void ecrire(Dossier d, LocalDate date, Map<String, Integer> credits, Map<String, Integer> debits) {
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        int totalCredit = 0;
        int totalDebit = 0;
        for (var e : credits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "Produit " + e.getKey(), BigDecimal.ZERO, new BigDecimal(e.getValue()), null));
            totalCredit += e.getValue();
        }
        for (var e : debits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "Charge " + e.getKey(), new BigDecimal(e.getValue()), BigDecimal.ZERO, null));
            totalDebit += e.getValue();
        }
        int ecart = totalCredit - totalDebit;
        UUID banque = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "521").orElseThrow().getId();
        if (ecart > 0) {
            lignes.add(new LigneEcritureRequest(banque, "Banque", new BigDecimal(ecart), BigDecimal.ZERO, null));
        } else if (ecart < 0) {
            lignes.add(new LigneEcritureRequest(banque, "Banque", BigDecimal.ZERO, new BigDecimal(-ecart), null));
        }
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal(d), date, "Scenario " + date, null, lignes), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
    }

    /** Scenario complet : 33 postes alimentes (hors RD, RF), resultat net attendu 537 000. */
    private void scenarioComplet(Dossier d, LocalDate date) {
        Map<String, Integer> credits = new LinkedHashMap<>();
        credits.put("701", 1_000_000); credits.put("702", 100_000); credits.put("705", 50_000); credits.put("707", 30_000); credits.put("73", 10_000);
        credits.put("72", 5_000); credits.put("71", 7_000); credits.put("75", 3_000); credits.put("781", 2_000); credits.put("791", 9_000); credits.put("77", 11_000);
        credits.put("82", 40_000); credits.put("84", 5_000);
        Map<String, Integer> debits = new LinkedHashMap<>();
        debits.put("601", 400_000); debits.put("6031", 20_000); debits.put("602", 60_000); debits.put("604", 15_000); debits.put("61", 8_000); debits.put("62", 12_000);
        debits.put("63", 6_000); debits.put("64", 4_000); debits.put("65", 3_000); debits.put("66", 120_000); debits.put("681", 25_000); debits.put("67", 14_000);
        debits.put("81", 35_000); debits.put("83", 2_000); debits.put("87", 1_000); debits.put("89", 10_000);
        ecrire(d, date, credits, debits);
    }

    private static Line ligne(StatementResult etat, String code) {
        return etat.lines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow();
    }

    private ExerciceComptable exerciceCourant(Dossier d) {
        return exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow();
    }

    private long statements() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
    }

    // ------------------------------------------------------------------ compte de resultat complet

    @Test
    void compteDeResultatComplet_valeursAttenduesCalculeesALaMain_etMappingParLeResolveurUnique() {
        Dossier d = nouveauDossier(false);
        scenarioComplet(d, LocalDate.now());

        StatementResult cr = incomeStatementService.generer(d.id(), exerciceCourant(d).getId(), d.expert());

        // postes
        assertThat(ligne(cr, "TA").amountN()).isEqualByComparingTo("1000000");
        assertThat(ligne(cr, "RA").amountN()).isEqualByComparingTo("-400000");
        assertThat(ligne(cr, "RB").amountN()).isEqualByComparingTo("-20000");
        assertThat(ligne(cr, "TG").amountN()).isEqualByComparingTo("7000");        // TG -> 71
        assertThat(ligne(cr, "TG").accounts()).extracting(a -> a.number()).containsExactly("71900");
        assertThat(ligne(cr, "RK").amountN()).isEqualByComparingTo("-120000");     // classe 66 = charges de PERSONNEL
        assertThat(ligne(cr, "RM").amountN()).isEqualByComparingTo("-14000");      // classe 67 = frais FINANCIERS
        assertThat(ligne(cr, "RH").amountN()).isEqualByComparingTo("-18000");      // 62 + 63
        assertThat(ligne(cr, "RD").amountN()).isEqualByComparingTo("0");           // poste sans mouvement
        // soldes intermediaires en cascade
        assertThat(ligne(cr, "XA").amountN()).isEqualByComparingTo("580000");      // marge commerciale
        assertThat(ligne(cr, "XB").amountN()).isEqualByComparingTo("1180000");     // chiffre d'affaires
        assertThat(ligne(cr, "XC").amountN()).isEqualByComparingTo("679000");      // valeur ajoutee
        assertThat(ligne(cr, "XD").amountN()).isEqualByComparingTo("559000");      // EBE
        assertThat(ligne(cr, "XE").amountN()).isEqualByComparingTo("543000");      // resultat d'exploitation
        assertThat(ligne(cr, "XF").amountN()).isEqualByComparingTo("-3000");       // resultat financier
        assertThat(ligne(cr, "XG").amountN()).isEqualByComparingTo("540000");      // RAO
        // HAO : classe 8 prise en compte
        assertThat(ligne(cr, "TN").amountN()).isEqualByComparingTo("40000");
        assertThat(ligne(cr, "RO").amountN()).isEqualByComparingTo("-35000");
        assertThat(ligne(cr, "XH").amountN()).isEqualByComparingTo("8000");        // resultat HAO
        assertThat(ligne(cr, "RQ").amountN()).isEqualByComparingTo("-1000");
        assertThat(ligne(cr, "RS").amountN()).isEqualByComparingTo("-10000");
        assertThat(ligne(cr, "XI").amountN()).isEqualByComparingTo("537000");      // resultat net

        // tout est mappe, coherent avec le grand livre ; aucun constat
        assertThat(cr.unmapped()).isEmpty();
        assertThat(cr.ambiguous()).isEmpty();
        assertThat(cr.controls()).isEmpty();
        // pas d'exercice precedent : N-1 absent (jamais invente)
        assertThat(cr.exercicePrecedentId()).isNull();
        assertThat(ligne(cr, "TA").amountNMinus1()).isNull();
        // structure, mapping et formules VERIFIED / DIRECT_PRIMARY : l'etat est READY
        assertThat(cr.status()).isEqualTo(Status.READY);
        assertThat(cr.statusReasons()).isEmpty();
        assertThat(cr.framework()).isEqualTo("SYSCOHADA_REVISE_AUDCIF_2017");
        assertThat(cr.definitionHash()).hasSize(64);
        assertThat(cr.dataset().fingerprint()).hasSize(64);
    }

    @Test
    void nEtNMoins1_surLesExercicesReels() {
        Dossier d = nouveauDossier(false);
        var exN = exerciceCourant(d);
        LocalDate debutN1 = exN.getDateDebut().minusMonths(18);      // exercice precedent de 18 mois, pas « date moins un an »
        LocalDate finN1 = exN.getDateDebut().minusDays(1);
        jdbc.update("insert into exercice_comptable (entreprise_id, date_debut, date_fin, statut) values (?, ?, ?, 'OUVERT')", d.id(), debutN1, finN1);
        ecrire(d, debutN1.plusDays(10), Map.of("701", 300_000), Map.of("601", 100_000));
        scenarioComplet(d, LocalDate.now());

        StatementResult cr = incomeStatementService.generer(d.id(), exN.getId(), d.expert());

        assertThat(cr.exercicePrecedentId()).isNotNull();
        assertThat(cr.precedentDebut()).isEqualTo(debutN1);
        assertThat(cr.precedentFin()).isEqualTo(finN1);
        assertThat(ligne(cr, "TA").amountN()).isEqualByComparingTo("1000000");
        assertThat(ligne(cr, "TA").amountNMinus1()).isEqualByComparingTo("300000");
        assertThat(ligne(cr, "RA").amountNMinus1()).isEqualByComparingTo("-100000");
        assertThat(ligne(cr, "XA").amountNMinus1()).isEqualByComparingTo("200000");
        assertThat(ligne(cr, "XI").amountNMinus1()).isEqualByComparingTo("200000");
        assertThat(ligne(cr, "XI").amountN()).isEqualByComparingTo("537000");        // N n'est pas pollue par N-1
        assertThat(ligne(cr, "RK").amountNMinus1()).isEqualByComparingTo("0");
    }

    @Test
    void ecritureDeCloture_n_annule_pas_le_resultat_de_N_mais_reste_visible_au_grand_livre_legal() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        ecrire(d, LocalDate.now(), Map.of("701", 1_000_000), Map.of());

        assertThat(ligne(incomeStatementService.generer(d.id(), ex.getId(), d.expert()), "XI").amountN()).isEqualByComparingTo("1000000");   // avant cloture

        clotureService.cloturerAvecConfirmation(d.id(), ex.getId(), d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test du compte de resultat"));

        StatementResult apres = incomeStatementService.generer(d.id(), ex.getId(), d.expert());
        assertThat(ligne(apres, "TA").amountN()).isEqualByComparingTo("1000000");     // apres cloture : TOUJOURS 1 000 000
        assertThat(ligne(apres, "XB").amountN()).isEqualByComparingTo("1000000");
        assertThat(ligne(apres, "XI").amountN()).isEqualByComparingTo("1000000");
        assertThat(apres.controls()).isEmpty();
        // l'ecriture technique est bien au grand livre legal : compte de produit solde, cloture de nature CLOSING
        assertThat(jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ? and nature = 'CLOSING'", Long.class, d.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select coalesce(sum(l.debit - l.credit), 0) from ligne_ecriture l join compte_comptable c on c.id = l.compte_id "
                + "where c.entreprise_id = ? and c.numero = '701900'", BigDecimal.class, d.id())).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ comptes non mappes -> Finding

    @Test
    void compteNonMappe_estExpose_ecartDeResultatSignale_etFindingUniqueEtIdempotent() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        ecrire(d, LocalDate.now(), Map.of("701", 100_000, "708", 5_000), Map.of());     // 708 : aucune regle verifiee

        StatementResult cr = incomeStatementService.generer(d.id(), ex.getId(), d.expert());

        assertThat(cr.unmapped()).extracting(a -> a.number()).containsExactly("708900");
        assertThat(ligne(cr, "XI").amountN()).isEqualByComparingTo("100000");              // jamais range dans la premiere rubrique plausible
        assertThat(cr.controls()).extracting(ControlResult::code).containsExactlyInAnyOrder(ControlCodes.UNMAPPED_ACCOUNT, ControlCodes.RESULT_MISMATCH);
        assertThat(cr.status()).isEqualTo(Status.DRAFT);

        // publication des constats : un Finding actif par code, jamais de doublon
        // (la synchronisation proactive a deja publie le constat du compte de resultat ET celui du bilan des la validation de l'ecriture : un constat actif par etat, jamais de doublon)
        controlPublisher.publierCompteResultat(d.id(), ex.getId(), d.expert());
        var avant = findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNMAPPED_ACCOUNT)).stream().map(f -> f.getId()).sorted().toList();
        controlPublisher.publierCompteResultat(d.id(), ex.getId(), d.expert());
        assertThat(avant).hasSize(2);
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNMAPPED_ACCOUNT)).stream().map(f -> f.getId()).sorted().toList()).isEqualTo(avant);
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.RESULT_MISMATCH))).isNotEmpty().hasSizeLessThanOrEqualTo(2);   // au plus un par etat (compte de resultat, bilan)
    }

    // ------------------------------------------------------------------ Bilan (partiel) et TFT

    @Test
    void bilan_capitalEnBanque_estReady_BZegalDZ_etCJegalXI() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        UUID capital = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "101").orElseThrow().getId();
        UUID banque = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "521").orElseThrow().getId();
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal(d), LocalDate.now(), "Capital", null, List.of(
                new LigneEcritureRequest(banque, "Banque", new BigDecimal("700000"), BigDecimal.ZERO, null),
                new LigneEcritureRequest(capital, "Capital", BigDecimal.ZERO, new BigDecimal("700000"), null))), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());

        var bilan = balanceSheetService.bilan(d.id(), ex.getId(), d.expert());

        assertThat(ligne(bilan.actif(), "BS").amountN()).isEqualByComparingTo("700000");
        assertThat(ligne(bilan.actif(), "BS").accounts()).extracting(a -> a.number()).containsExactly("521");
        assertThat(ligne(bilan.actif(), "BT").amountN()).isEqualByComparingTo("700000");
        assertThat(ligne(bilan.actif(), "BZ").amountN()).isEqualByComparingTo("700000");
        assertThat(ligne(bilan.passif(), "CA").amountN()).isEqualByComparingTo("700000");
        assertThat(ligne(bilan.passif(), "CP").amountN()).isEqualByComparingTo("700000");
        assertThat(ligne(bilan.passif(), "DZ").amountN()).isEqualByComparingTo("700000");
        assertThat(bilan.totalActifN()).isEqualByComparingTo(bilan.totalPassifN());
        assertThat(bilan.resultatBilanN()).isEqualByComparingTo(bilan.resultatCompteResultatN());
        assertThat(bilan.status()).isEqualTo(Status.READY);
        assertThat(bilan.controls()).isEmpty();
        assertThat(bilan.actif().unmapped()).isEmpty();
    }

    @Test
    void bilan_compteNonMappe_ou_ligneNonCodeeAvecSolde_donneIncomplete_etFinding() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        // 404 : fournisseurs d'immobilisations courantes, reserve PARTIAL -> non mappe ; 211 (AE) : ligne PARTIAL portant un solde
        ecrire(d, LocalDate.now(), Map.of("404", 1_000), Map.of("211", 1_000));

        var bilan = balanceSheetService.bilan(d.id(), ex.getId(), d.expert());

        assertThat(bilan.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(bilan.actif().unmapped()).extracting(a -> a.number()).contains("404900");
        assertThat(bilan.controls()).extracting(ControlResult::code).contains(ControlCodes.UNMAPPED_ACCOUNT);
        assertThat(bilan.reasons()).anyMatch(r -> r.contains("AE"));
        assertThat(ligne(bilan.actif(), "AE").complete()).isFalse();
        // (deja publies par la synchronisation proactive des la validation de l'ecriture : republier ne cree aucun doublon)
        var avant = findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNMAPPED_ACCOUNT)).stream().map(f -> f.getId()).sorted().toList();
        controlPublisher.publierBilan(d.id(), ex.getId(), d.expert());
        controlPublisher.publierBilan(d.id(), ex.getId(), d.expert());
        assertThat(avant).isNotEmpty();
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNMAPPED_ACCOUNT)).stream().map(f -> f.getId()).sorted().toList()).isEqualTo(avant);
    }

    @Test
    void tft_surDossierVide_zeroPartout_etInvariantsDeFormule() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        StatementResult tft = cashFlowStatementService.generer(d.id(), ex.getId(), d.expert());
        assertThat(tft.status()).isEqualTo(Status.READY);
        assertThat(tft.lines()).hasSize(25).allSatisfy(l -> assertThat(l.amountN()).isEqualByComparingTo("0"));

        // invariants officiels sur des flux fournis
        Map<String, BigDecimal> flux = new LinkedHashMap<>();
        flux.put("FA", new BigDecimal("500000")); flux.put("FB", new BigDecimal("-10000")); flux.put("FC", new BigDecimal("-20000")); flux.put("FD", new BigDecimal("-30000"));
        flux.put("FE", new BigDecimal("15000")); flux.put("FF", new BigDecimal("-100000")); flux.put("FG", new BigDecimal("-200000")); flux.put("FH", BigDecimal.ZERO);
        flux.put("FI", new BigDecimal("50000")); flux.put("FJ", BigDecimal.ZERO); flux.put("FK", new BigDecimal("300000")); flux.put("FL", BigDecimal.ZERO);
        flux.put("FM", BigDecimal.ZERO); flux.put("FN", new BigDecimal("-40000")); flux.put("FO", new BigDecimal("100000")); flux.put("FP", BigDecimal.ZERO);
        flux.put("FQ", new BigDecimal("-60000"));
        Map<String, BigDecimal> coherent = cashFlowStatementService.evaluer(flux, new BigDecimal("100000"), new BigDecimal("605000"));
        assertThat(coherent.get("ZB")).isEqualByComparingTo("455000");
        assertThat(coherent.get("ZG")).isEqualByComparingTo("505000");
        assertThat(cashFlowStatementService.controler(d.id(), ex.getId(), coherent)).isEmpty();
        Map<String, BigDecimal> incoherent = cashFlowStatementService.evaluer(flux, new BigDecimal("100000"), new BigDecimal("600000"));
        assertThat(cashFlowStatementService.controler(d.id(), ex.getId(), incoherent)).extracting(ControlResult::code).containsExactly(ControlCodes.CASH_FLOW_MISMATCH);
    }

    // ------------------------------------------------------------------ drill-down

    @Test
    void drillDown_rubrique_comptes_solde_ecritures_et_lineage_jusqua_la_facture() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        UUID client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Drill", null, d.expert()).id();
        UUID produit = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "701").orElseThrow().getId();
        var facture = facturationService.validerFacture(d.id(), facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client, LocalDate.now(),
                LocalDate.now().plusDays(30), "Vente", List.of(new LigneFactureRequest("Marchandise", BigDecimal.ONE, new BigDecimal("100000"), BigDecimal.ZERO, null))),
                d.expert()).id(), produit, d.expert());

        var rubrique = drillDownService.detailler(d.id(), ex.getId(), StatementType.COMPTE_RESULTAT, "TA", null, d.expert());
        assertThat(rubrique.accounts()).extracting(a -> a.number()).containsExactly("701");
        assertThat(rubrique.accounts().get(0).amountN()).isEqualByComparingTo("100000");

        var compte = drillDownService.detailler(d.id(), ex.getId(), StatementType.COMPTE_RESULTAT, "TA", produit, d.expert()).account();
        assertThat(compte.closing()).isEqualByComparingTo("-100000");
        assertThat(compte.movementCredit()).isEqualByComparingTo("100000");
        assertThat(compte.entries()).hasSize(1);
        assertThat(compte.entries().get(0).ecritureId()).isEqualTo(facture.ecritureId());
        assertThat(compte.entries().get(0).lineage()).hasSize(1);
        assertThat(compte.entries().get(0).lineage().get(0).sourceType()).isEqualTo("FACTURE");
        assertThat(compte.entries().get(0).lineage().get(0).sourceId()).isEqualTo(facture.id());
        assertThat(compte.entries().get(0).lineage().get(0).sourceEvent()).isEqualTo("VALIDATION");

        // un compte qui ne compose pas la ligne : 404
        assertThatThrownBy(() -> drillDownService.detailler(d.id(), ex.getId(), StatementType.COMPTE_RESULTAT, "TB", produit, d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    // ------------------------------------------------------------------ snapshot FINAL

    @Test
    void finalisation_refusee_sansCloture_etBilanTftJamaisFinalises() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        ecrire(d, LocalDate.now(), Map.of("701", 1_000), Map.of());

        // CR READY mais exercice ouvert : refuse
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex.getId(), StatementType.COMPTE_RESULTAT, d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cloture");
        // Bilan, TFT et dossier : READY mais exercice ouvert -> refuse
        for (StatementType type : List.of(StatementType.BILAN_ACTIF, StatementType.BILAN_PASSIF, StatementType.TFT)) {
            assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex.getId(), type, d.expert()))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cloture");
        }
        // les Notes ne sont pas READY (applicabilites a determiner) : le dossier n'est jamais finalise
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex.getId(), StatementType.DOSSIER_COMPLET, d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Notes");
        assertThat(snapshotRepository.findByEntrepriseIdAndExerciceIdOrderByCreatedAtDesc(d.id(), ex.getId())).isEmpty();
    }

    @Test
    void finalisationDuCompteDeResultat_etatReel_fige_la_definition_les_montants_et_les_controles_et_reste_immuable() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        ecrire(d, LocalDate.now(), Map.of("701", 1_000_000), Map.of());
        clotureService.cloturerAvecConfirmation(d.id(), ex.getId(), d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test du snapshot"));
        StatementResult etat = incomeStatementService.generer(d.id(), ex.getId(), d.expert());
        assertThat(etat.status()).isEqualTo(Status.READY);

        var snapshot = snapshotService.finaliser(d.id(), ex.getId(), StatementType.COMPTE_RESULTAT, d.expert());

        assertThat(snapshot.status()).isEqualTo("FINAL");
        assertThat(snapshot.framework()).isEqualTo("SYSCOHADA_REVISE_AUDCIF_2017");
        assertThat(snapshot.definitionVersion()).isEqualTo("2");
        assertThat(snapshot.definitionHash()).isEqualTo(etat.definitionHash());
        assertThat(snapshot.datasetFingerprint()).isEqualTo(etat.dataset().fingerprint());
        assertThat(snapshot.payload().get("status").asText()).isEqualTo("FINAL");
        assertThat(snapshot.payload().get("lines").size()).isEqualTo(42);
        assertThat(snapshot.payload().get("controls").size()).isZero();
        assertThat(snapshotService.lire(d.id(), snapshot.id(), d.expert()).payload().get("definitionHash").asText()).isEqualTo(etat.definitionHash());
        assertThat(evenements("ETAT_FINANCIER_FINALISE")).anyMatch(e -> String.valueOf(snapshot.id()).equals(e.getEntiteId()));

        // une seconde finalisation de la meme version reglementaire : refusee
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex.getId(), StatementType.COMPTE_RESULTAT, d.expert())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja finalise");

        // immuable jusque dans la base : ni UPDATE ni DELETE
        assertThatThrownBy(() -> jdbc.update("update financial_statement_snapshot set payload = 'x' where id = ?", snapshot.id())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immuable");
        assertThatThrownBy(() -> jdbc.update("delete from financial_statement_snapshot where id = ?", snapshot.id())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immuable");
        FinancialStatementSnapshot relu = snapshotRepository.findById(snapshot.id()).orElseThrow();
        assertThat(relu.getPayloadSha256()).isEqualTo(snapshot.payloadSha256());
    }

    // ------------------------------------------------------------------ multi-tenant

    @Test
    void isolationMultiTenant_etatsEtSnapshots() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        var exA = exerciceCourant(a);
        ecrire(a, LocalDate.now(), Map.of("701", 5_000), Map.of());

        for (var appel : List.<java.util.function.Supplier<?>>of(
                () -> incomeStatementService.generer(b.id(), exA.getId(), b.expert()),
                () -> balanceSheetService.actif(b.id(), exA.getId(), b.expert()),
                () -> balanceSheetService.passif(b.id(), exA.getId(), b.expert()),
                () -> cashFlowStatementService.generer(b.id(), exA.getId(), b.expert()),
                () -> drillDownService.detailler(b.id(), exA.getId(), StatementType.COMPTE_RESULTAT, "TA", null, b.expert()),
                () -> snapshotService.finaliser(b.id(), exA.getId(), StatementType.COMPTE_RESULTAT, b.expert()))) {
            assertThatThrownBy(appel::get).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
        // l'etat de B (autre exercice) ne contient rien de A
        StatementResult etatB = incomeStatementService.generer(b.id(), exerciceCourant(b).getId(), b.expert());
        assertThat(ligne(etatB, "TA").amountN()).isEqualByComparingTo("0");
        assertThat(etatB.unmapped()).isEmpty();
    }

    // ------------------------------------------------------------------ performance : nombre de requetes

    @Test
    void nombreDeRequetes_ducompteDeResultat_neDependPasDuNombreDeComptes() {
        Dossier peu = nouveauDossier(false);
        Dossier beaucoup = nouveauDossier(false);
        ecrire(peu, LocalDate.now(), Map.of("701", 10), Map.of());
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(beaucoup.id()));
            c.setNumero("7011" + String.format("%02d", i));
            c.setIntitule("Ventes " + i);
            c.setLettrable(false);
            c = compteComptableRepository.save(c);
            lignes.add(new LigneEcritureRequest(c.getId(), "Vente " + i, BigDecimal.ZERO, new BigDecimal("10"), null));
        }
        lignes.add(new LigneEcritureRequest(compteComptableRepository.findByEntrepriseIdAndNumero(beaucoup.id(), "521").orElseThrow().getId(), "Banque", new BigDecimal("400"), BigDecimal.ZERO, null));
        UUID id = ecritureService.creerManuelle(beaucoup.id(), new CreerEcritureRequest(journal(beaucoup), LocalDate.now(), "40 comptes", null, lignes), beaucoup.expert()).id();
        ecritureService.valider(beaucoup.id(), id, beaucoup.expert());

        UUID exPeu = exerciceCourant(peu).getId();
        UUID exBeaucoup = exerciceCourant(beaucoup).getId();
        incomeStatementService.generer(peu.id(), exPeu, peu.expert());     // echauffement
        long avant = statements();
        incomeStatementService.generer(peu.id(), exPeu, peu.expert());
        long petite = statements() - avant;
        avant = statements();
        StatementResult grand = incomeStatementService.generer(beaucoup.id(), exBeaucoup, beaucoup.expert());
        long grande = statements() - avant;

        assertThat(ligne(grand, "TA").accounts()).hasSize(40);
        assertThat(grande).as("requetes pour 40 comptes vs 1 compte").isEqualTo(petite);
        assertThat(grande).isLessThanOrEqualTo(12);
    }
}
