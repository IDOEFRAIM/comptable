package com.example.comptable.etatsfinanciers;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.CashFlowEvent;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService;
import com.example.comptable.etatsfinanciers.service.CashFlowStatementService;
import com.example.comptable.etatsfinanciers.service.IncomeStatementService;
import com.example.comptable.etatsfinanciers.service.NotesService;
import com.example.comptable.etatsfinanciers.service.StatementControlPublisher;
import com.example.comptable.etatsfinanciers.service.StatementDrillDownService;
import com.example.comptable.etatsfinanciers.service.StatementResult;
import com.example.comptable.etatsfinanciers.service.StatementResult.Line;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
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
 * Pre-finalisation du dossier financier sur PostgreSQL : FC sans variation de depreciation, CAFG (composants non resolus), evenements de tresorerie (financement,
 * investissement, cession, non cash), scenario E2E complet (N-1 et N), Notes (21, 22, 34, applicabilite, etats), snapshot DOSSIER_COMPLET de succes et refus, requetes.
 */
class PreFinalisationEtatsFinanciersTest extends BankTestBase {

    @Autowired BalanceSheetService balanceSheetService;
    @Autowired CashFlowStatementService cashFlowStatementService;
    @Autowired IncomeStatementService incomeStatementService;
    @Autowired NotesService notesService;
    @Autowired StatementControlPublisher controlPublisher;
    @Autowired StatementDrillDownService drillDownService;
    @Autowired FinancialStatementSnapshotService snapshotService;
    @Autowired FinancialStatementSnapshotRepository snapshotRepository;
    @Autowired FinancialStatementDefinitionRegistry registry;
    @Autowired EcritureComptableService ecritureService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    // ------------------------------------------------------------------ outils

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

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return r;
    }

    private void ecrire(Dossier d, LocalDate date, Map<String, Integer> debits, Map<String, Integer> credits) {
        ecrire(d, date, debits, credits, null);
    }

    private void ecrire(Dossier d, LocalDate date, Map<String, Integer> debits, Map<String, Integer> credits, CashFlowEvent evenement) {
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        int debit = 0;
        int credit = 0;
        for (var e : debits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "D " + e.getKey(), new BigDecimal(e.getValue()), BigDecimal.ZERO, null));
            debit += e.getValue();
        }
        for (var e : credits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "C " + e.getKey(), BigDecimal.ZERO, new BigDecimal(e.getValue()), null));
            credit += e.getValue();
        }
        assertThat(debit).isEqualTo(credit);
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal(d), date, "Scenario", null, lignes, evenement), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
    }

    private UUID exercice(Dossier d) {
        return exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
    }

    /** Exercice N-1 de 18 mois (exercice precedent reel) ; retourne sa date de debut. */
    private LocalDate exercicePrecedent(Dossier d) {
        var exN = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow();
        LocalDate debut = exN.getDateDebut().minusMonths(18);
        jdbc.update("insert into exercice_comptable (entreprise_id, date_debut, date_fin, statut) values (?, ?, ?, 'OUVERT')", d.id(), debut, exN.getDateDebut().minusDays(1));
        return debut;
    }

    private static Line ligne(StatementResult etat, String code) {
        return etat.lines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow();
    }

    private static void assertMontant(StatementResult etat, String code, String attendu) {
        assertThat(ligne(etat, code).amountN()).as(code).isEqualByComparingTo(attendu);
    }

    private long statements() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
    }

    /**
     * Scenario financier : N-1 = capital et prime en numeraire, acquisition payee ; N = vente avec TVA, achat avec TVA, stock, encaissement partiel, paiement partiel, amortissement,
     * emprunt encaisse, remboursement partiel, incorporation de prime au capital (non cash), et (optionnel) interets.
     */
    private void scenarioFinancier(Dossier d, boolean avecInterets) {
        LocalDate n1 = exercicePrecedent(d).plusDays(10);
        ecrire(d, n1, m("521", 1_050_000), m("101", 1_000_000, "105", 50_000), CashFlowEvent.CAPITAL_CASH_INCREASE);
        ecrire(d, n1.plusDays(1), m("22", 400_000), m("521", 400_000));
        LocalDate n = LocalDate.now();
        ecrire(d, n, m("411", 708_000), m("701", 600_000, "4431", 108_000));
        ecrire(d, n, m("601", 250_000, "4452", 45_000), m("401", 295_000));
        ecrire(d, n, m("31", 80_000), m("6031", 80_000));
        ecrire(d, n, m("521", 500_000), m("411", 500_000));
        ecrire(d, n, m("401", 200_000), m("521", 200_000));
        ecrire(d, n, m("681", 40_000), m("282", 40_000));
        ecrire(d, n, m("521", 300_000), m("16", 300_000), CashFlowEvent.BORROWING_CASH_RECEIVED);
        ecrire(d, n, m("16", 100_000), m("521", 100_000), CashFlowEvent.BORROWING_OR_LEASE_DEBT_CASH_REPAID);
        ecrire(d, n, m("105", 50_000), m("101", 50_000));      // incorporation de prime au capital : aucun flux
        if (avecInterets) {
            ecrire(d, n, m("67", 15_000), m("521", 15_000));
        }
    }

    // ------------------------------------------------------------------ FC : une depreciation n'est pas un flux

    @Test
    void fc_stockInchange_depreciation39Augmente_aucunFluxDeTresorerie() {
        Dossier d = nouveauDossier(false);
        LocalDate n1 = exercicePrecedent(d).plusDays(5);
        ecrire(d, n1, m("31", 100), m("101", 100));              // stock apporte en N-1
        ecrire(d, LocalDate.now(), m("691", 100), m("39", 100));   // N : depreciation de 100, stock brut inchange

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        assertMontant(tft, "FC", "0");
        assertMontant(tft, "FA", "0");
        assertMontant(tft, "ZG", "0");
        assertMontant(tft, "ZH", "0");
        assertThat(tft.status()).isEqualTo(Status.READY);
        assertThat(tft.controls()).extracting(ControlResult::code).doesNotContain(ControlCodes.CASH_FLOW_MISMATCH);
        // drill-down : FC n'a aucun compte (rien n'a varie au brut)
        assertThat(drillDownService.detailler(d.id(), exercice(d), StatementType.TFT, "FC", null, d.expert()).accounts()).isEmpty();
    }

    // ------------------------------------------------------------------ CAFG

    @Test
    void cafg_interetsFinanciers_composantNonResolu_findingIndiqueLeCompte() {
        Dossier d = nouveauDossier(false);
        ecrire(d, LocalDate.now(), m("521", 100), m("701", 100));
        ecrire(d, LocalDate.now(), m("67", 10), m("521", 10));
        UUID ex = exercice(d);

        StatementResult tft = controlPublisher.publierTft(d.id(), ex, d.expert());

        assertThat(ligne(tft, "FA").amountN()).isNull();
        assertThat(ligne(tft, "FA").incompleteReasons()).anyMatch(r -> r.contains("UNRESOLVED_CAFG_COMPONENT"));
        ControlResult constat = tft.controls().stream().filter(c -> c.code().equals(ControlCodes.UNRESOLVED_CAFG_COMPONENT)).findFirst().orElseThrow();
        assertThat(constat.metadata().toString()).contains("67900");
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNRESOLVED_CAFG_COMPONENT))).hasSize(1);
        assertThat(tft.controls()).extracting(ControlResult::code).doesNotContain(ControlCodes.CASH_FLOW_MISMATCH);
    }

    @Test
    void cafg_sansMouvementSurLesComptesPartial_termesEgauxAZero_etCafgCalculee() {
        Dossier d = nouveauDossier(false);
        ecrire(d, LocalDate.now(), m("521", 100), m("701", 100));
        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());
        assertMontant(tft, "FA", "100");
        assertThat(tft.controls()).extracting(ControlResult::code).doesNotContain(ControlCodes.UNRESOLVED_CAFG_COMPONENT);
    }

    // ------------------------------------------------------------------ financement : evenements qualifies

    @Test
    void financement_evenementsQualifies_FK_FO_FQ_etNonQualifie_incomplet() {
        Dossier d = nouveauDossier(false);
        ecrire(d, LocalDate.now(), m("521", 700), m("101", 700), CashFlowEvent.CAPITAL_CASH_INCREASE);
        ecrire(d, LocalDate.now(), m("521", 1000), m("16", 1000), CashFlowEvent.BORROWING_CASH_RECEIVED);
        ecrire(d, LocalDate.now(), m("16", 300), m("521", 300), CashFlowEvent.BORROWING_OR_LEASE_DEBT_CASH_REPAID);

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        assertMontant(tft, "FK", "700");
        assertMontant(tft, "FO", "1000");
        assertMontant(tft, "FQ", "-300");
        assertMontant(tft, "ZF", "1400");
        assertMontant(tft, "ZH", "1400");
        assertThat(tft.status()).isEqualTo(Status.READY);
        var composants = drillDownService.detailler(d.id(), exercice(d), StatementType.TFT, "FO", null, d.expert()).components();
        assertThat(composants).hasSize(1);
        assertThat(composants.get(0).evenement()).isEqualTo("BORROWING_CASH_RECEIVED");
        assertThat(composants.get(0).montant()).isEqualByComparingTo("1000");

        // meme mouvement sans evenement qualifie : jamais estime
        Dossier e = nouveauDossier(false);
        ecrire(e, LocalDate.now(), m("521", 700), m("101", 700));
        StatementResult nq = cashFlowStatementService.generer(e.id(), exercice(e), e.expert());
        assertThat(ligne(nq, "FK").amountN()).isNull();
        assertThat(nq.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(nq.controls()).extracting(ControlResult::code).contains(ControlCodes.UNQUALIFIED_CASH_EVENT);
    }

    @Test
    void financement_evenementIncoherentAvecLesLignes_estRefuseCommeNonQualifie() {
        Dossier d = nouveauDossier(false);
        // declare comme emprunt encaisse alors que la contrepartie est du capital
        ecrire(d, LocalDate.now(), m("521", 700), m("101", 700), CashFlowEvent.BORROWING_CASH_RECEIVED);
        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());
        assertThat(ligne(tft, "FO").amountN()).isNull();
        assertThat(tft.status()).isEqualTo(Status.INCOMPLETE);
    }

    // ------------------------------------------------------------------ non cash

    @Test
    void nonCash_incorporationDePrime_conversionDeDette_locationAcquisition_aucunFauxFlux() {
        Dossier d = nouveauDossier(false);
        LocalDate n1 = exercicePrecedent(d).plusDays(3);
        ecrire(d, n1, m("521", 500), m("105", 500), CashFlowEvent.CAPITAL_CASH_INCREASE);
        ecrire(d, n1, m("521", 200), m("16", 200), CashFlowEvent.BORROWING_CASH_RECEIVED);
        LocalDate n = LocalDate.now();
        ecrire(d, n, m("105", 500), m("101", 500));                // incorporation de prime au capital
        ecrire(d, n, m("16", 100), m("101", 100));                 // conversion de dette en capital
        ecrire(d, n, m("22", 300), m("17", 300));                  // location-acquisition initiale

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        for (String code : List.of("FF", "FG", "FH", "FI", "FJ", "FK", "FL", "FM", "FN", "FO", "FP", "FQ")) {
            assertMontant(tft, code, "0");
        }
        assertMontant(tft, "ZG", "0");
        assertMontant(tft, "ZA", "700");
        assertMontant(tft, "ZH", "700");
        assertThat(tft.status()).isEqualTo(Status.READY);
    }

    // ------------------------------------------------------------------ investissement

    @Test
    void investissement_achatACredit_puisPaiementQualifie_FGsurLeCashReel() {
        Dossier d = nouveauDossier(false);
        ecrire(d, LocalDate.now(), m("521", 1000), m("701", 1000));
        ecrire(d, LocalDate.now(), m("22", 300), m("481", 300));       // achat a credit : aucun cash
        UUID ex = exercice(d);
        StatementResult avant = cashFlowStatementService.generer(d.id(), ex, d.expert());
        assertMontant(avant, "FG", "0");
        assertMontant(avant, "ZH", "1000");
        assertMontant(avant, "ZG", "1000");

        ecrire(d, LocalDate.now(), m("481", 100), m("521", 100));      // paiement partiel sans evenement : non qualifie
        StatementResult nq = cashFlowStatementService.generer(d.id(), ex, d.expert());
        assertThat(ligne(nq, "FG").amountN()).isNull();
        assertThat(nq.status()).isEqualTo(Status.INCOMPLETE);

        Dossier e = nouveauDossier(false);
        ecrire(e, LocalDate.now(), m("521", 1000), m("701", 1000));
        ecrire(e, LocalDate.now(), m("22", 300), m("481", 300));
        ecrire(e, LocalDate.now(), m("481", 100), m("521", 100), CashFlowEvent.TANGIBLE_ASSET_ACQUISITION_CASH_PAID);
        StatementResult ok = cashFlowStatementService.generer(e.id(), exercice(e), e.expert());
        assertMontant(ok, "FG", "-100");                                // seule la part payee
        assertMontant(ok, "ZH", "900");
        assertMontant(ok, "ZG", "900");
        assertThat(ok.status()).isEqualTo(Status.READY);
    }

    // ------------------------------------------------------------------ E2E cession

    @Test
    void cession_FIegalAuCashEncaisse_pasAuPrixComptable_miseAuRebutZero() {
        Dossier d = nouveauDossier(false);
        LocalDate n1 = exercicePrecedent(d).plusDays(3);
        ecrire(d, n1, m("521", 1000), m("101", 1000), CashFlowEvent.CAPITAL_CASH_INCREASE);
        ecrire(d, n1, m("22", 500), m("521", 500));
        LocalDate n = LocalDate.now();
        // cession a 600, encaissee 400, 200 restant a recevoir ; sortie de l'actif a la valeur comptable de 500
        ecrire(d, n, m("521", 400, "485", 200), m("82", 600), CashFlowEvent.TANGIBLE_INTANGIBLE_ASSET_SALE_CASH_RECEIVED);
        ecrire(d, n, m("81", 500), m("22", 500));

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        assertMontant(tft, "FI", "400");                                // cash reellement encaisse, pas 600
        assertMontant(tft, "FG", "0");
        assertMontant(tft, "ZA", "500");
        assertMontant(tft, "ZH", "900");
        assertMontant(tft, "ZG", "400");
        assertThat(tft.status()).isEqualTo(Status.READY);
        var composants = drillDownService.detailler(d.id(), exercice(d), StatementType.TFT, "FI", null, d.expert()).components();
        assertThat(composants).hasSize(1);
        assertThat(composants.get(0).montant()).isEqualByComparingTo("400");

        // cession non encaissee + mise au rebut : aucun flux
        Dossier e = nouveauDossier(false);
        LocalDate e1 = exercicePrecedent(e).plusDays(3);
        ecrire(e, e1, m("521", 1000), m("101", 1000), CashFlowEvent.CAPITAL_CASH_INCREASE);
        ecrire(e, e1, m("22", 500), m("521", 500));
        ecrire(e, LocalDate.now(), m("485", 600), m("82", 600));
        ecrire(e, LocalDate.now(), m("81", 500), m("22", 500));
        StatementResult sans = cashFlowStatementService.generer(e.id(), exercice(e), e.expert());
        assertMontant(sans, "FI", "0");
        assertMontant(sans, "ZG", "0");
        assertThat(sans.status()).isEqualTo(Status.READY);

        // encaissement de cession non qualifie : INCOMPLETE + Finding explicite
        Dossier f = nouveauDossier(false);
        ecrire(f, LocalDate.now(), m("521", 400), m("82", 400));
        StatementResult nq = controlPublisher.publierTft(f.id(), exercice(f), f.expert());
        assertThat(ligne(nq, "FI").amountN()).isNull();
        assertThat(findingRepository.findActifsParCodes(f.id(), List.of(ControlCodes.UNQUALIFIED_CASH_EVENT))).hasSize(1);
    }

    // ------------------------------------------------------------------ E2E financier complet

    @Test
    void e2e_dossierFinancier_sansInterets_CRBilanTftReady_etInvariants() {
        Dossier d = nouveauDossier(false);
        scenarioFinancier(d, false);
        UUID ex = exercice(d);

        StatementResult cr = incomeStatementService.generer(d.id(), ex, d.expert());
        assertMontant(cr, "TA", "600000");
        assertMontant(cr, "XA", "430000");
        assertMontant(cr, "XD", "430000");
        assertMontant(cr, "XI", "390000");
        assertThat(cr.status()).isEqualTo(Status.READY);

        var bilan = balanceSheetService.bilan(d.id(), ex, d.expert());
        assertThat(bilan.status()).isEqualTo(Status.READY);
        assertThat(bilan.resultatBilanN()).isEqualByComparingTo(bilan.resultatCompteResultatN());           // CJ = XI
        assertThat(bilan.totalActifN()).isEqualByComparingTo("1843000");                                    // BZ
        assertThat(bilan.totalPassifN()).isEqualByComparingTo("1843000");                                   // DZ

        StatementResult tft = cashFlowStatementService.generer(d.id(), ex, d.expert());
        assertMontant(tft, "ZA", "650000");
        assertMontant(tft, "FA", "430000");
        assertMontant(tft, "FC", "-80000");
        assertMontant(tft, "FD", "-253000");
        assertMontant(tft, "FE", "203000");
        assertMontant(tft, "ZB", "300000");
        assertMontant(tft, "FG", "0");
        assertMontant(tft, "FO", "300000");
        assertMontant(tft, "FQ", "-100000");
        assertMontant(tft, "ZF", "200000");
        assertMontant(tft, "ZG", "500000");
        assertMontant(tft, "ZH", "1150000");
        assertThat(ligne(tft, "ZA").amountN().add(ligne(tft, "ZG").amountN())).isEqualByComparingTo(ligne(tft, "ZH").amountN());
        assertThat(tft.status()).isEqualTo(Status.READY);
        assertThat(tft.controls()).isEmpty();
        // le non-cash (incorporation de prime) n'apparait dans aucun flux
        assertMontant(tft, "FK", "0");
        // drill-down FO : ecriture, evenement, montant
        var fo = drillDownService.detailler(d.id(), ex, StatementType.TFT, "FO", null, d.expert());
        assertThat(fo.components()).extracting(c -> c.evenement()).containsExactly("BORROWING_CASH_RECEIVED");
    }

    @Test
    void e2e_dossierFinancier_avecInterets_tftIncomplet_findingCafg_maisFOetFQcalcules() {
        Dossier d = nouveauDossier(false);
        scenarioFinancier(d, true);
        UUID ex = exercice(d);

        StatementResult tft = controlPublisher.publierTft(d.id(), ex, d.expert());

        assertThat(tft.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(ligne(tft, "FA").amountN()).isNull();
        assertMontant(tft, "FO", "300000");
        assertMontant(tft, "FQ", "-100000");
        assertMontant(tft, "ZA", "650000");
        assertMontant(tft, "ZH", "1135000");
        assertThat(ligne(tft, "ZG").amountN()).isNull();
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNRESOLVED_CAFG_COMPONENT))).hasSize(1);
        assertThat(tft.controls()).extracting(ControlResult::code).doesNotContain(ControlCodes.CASH_FLOW_MISMATCH);
        // le Bilan reste READY (interets en charge, CJ = XI)
        assertThat(balanceSheetService.bilan(d.id(), ex, d.expert()).status()).isEqualTo(Status.READY);
    }

    // ------------------------------------------------------------------ Notes 21, 22, 34, applicabilite

    private static NotesService.NoteResult note(NotesService.NotesResult r, String code) {
        return r.notes().stream().filter(n -> n.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void notes21_22_totauxParFamille_etVentilationManuelle_jamaisAgregationInventee() {
        Dossier d = nouveauDossier(false);
        ecrire(d, LocalDate.now(), m("521", 1200), m("701", 1000, "707", 200));
        ecrire(d, LocalDate.now(), m("601", 300, "604", 50), m("521", 350));
        UUID ex = exercice(d);

        var notes = notesService.generer(d.id(), ex, NotesService.Decisions.aucune(), d.expert());

        var n21 = note(notes, "21");
        assertThat(n21.applicability()).isEqualTo("APPLICABLE");
        assertThat(n21.lines().stream().filter(l -> l.code().equals("TA")).findFirst().orElseThrow().amountN()).isEqualByComparingTo("1000");
        assertThat(n21.lines().stream().filter(l -> l.code().equals("TD")).findFirst().orElseThrow().amountN()).isEqualByComparingTo("200");
        assertThat(n21.status()).isEqualTo("INCOMPLETE");
        assertThat(n21.gaps()).anyMatch(g -> g.section().contains("region") && g.kind().equals("MANUAL"));
        var n22 = note(notes, "22");
        assertThat(n22.lines().stream().filter(l -> l.code().equals("RA")).findFirst().orElseThrow().amountN()).isEqualByComparingTo("300");
        assertThat(n22.lines().stream().filter(l -> l.code().equals("RE")).findFirst().orElseThrow().amountN()).isEqualByComparingTo("50");
        assertThat(n22.status()).isEqualTo("INCOMPLETE");
        assertThat(n22.gaps()).extracting(g -> g.noteCode()).containsOnly("22");
    }

    @Test
    void note34_dependDesEtatsSources_jamaisReadySiBilanOuTftIncomplete() {
        Dossier d = nouveauDossier(false);
        ecrire(d, LocalDate.now(), m("521", 100), m("701", 100));
        ecrire(d, LocalDate.now(), m("67", 10), m("521", 10));          // TFT INCOMPLETE (CAFG)
        UUID ex = exercice(d);

        var notes = notesService.generer(d.id(), ex, new NotesService.Decisions(null, Map.of("34", "APPLICABLE")), d.expert());

        var n34 = note(notes, "34");
        assertThat(n34.attached()).isTrue();
        assertThat(n34.status()).isEqualTo("INCOMPLETE");
        assertThat(n34.gaps()).anyMatch(g -> g.kind().equals("COMPUTED") && g.missingInformation().contains("TFT"));

        // avec des etats READY : la note reste a completer (formules d'indicateurs absentes du paquet reglementaire)
        Dossier e = nouveauDossier(false);
        ecrire(e, LocalDate.now(), m("521", 100), m("701", 100));
        var ok = note(notesService.generer(e.id(), exercice(e), new NotesService.Decisions(null, Map.of("34", "APPLICABLE")), e.expert()), "34");
        assertThat(ok.gaps()).noneMatch(g -> g.kind().equals("COMPUTED"));
        assertThat(ok.status()).isEqualTo("INCOMPLETE");
        assertThat(ok.lines().stream().filter(l -> l.code().equals("EBE")).findFirst().orElseThrow().amountN()).isEqualByComparingTo("100");
    }

    @Test
    void applicabilite_candidatesProuveesParLesComptes_qualitatifJamaisDeduit_validationUnique() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);

        var vide = notesService.generer(d.id(), ex, NotesService.Decisions.aucune(), d.expert());
        assertThat(note(vide, "6").applicability()).isEqualTo("NOT_APPLICABLE_CANDIDATE");     // aucun stock
        assertThat(note(vide, "4").applicability()).isEqualTo("NOT_APPLICABLE_CANDIDATE");     // aucune immobilisation financiere
        assertThat(note(vide, "9").applicability()).isEqualTo("NOT_APPLICABLE_CANDIDATE");     // aucun titre de placement
        for (String qualitative : List.of("16B", "16B bis", "16C", "35", "1", "2")) {
            assertThat(note(vide, qualitative).applicability()).as(qualitative).isIn("TO_DETERMINE", "UNDETERMINED");
        }
        assertThat(note(vide, "29").applicability()).isEqualTo("TO_DETERMINE");                // interets non comptabilises : extra-comptable
        assertThat(note(vide, "6").status()).isEqualTo("MANUAL_REQUIRED");
        assertThat(note(vide, "6").gaps()).extracting(g -> g.kind()).contains("VALIDATION");

        // un stock rend la Note 6 applicable (preuve par les soldes), jamais candidate
        ecrire(d, LocalDate.now(), m("31", 100), m("101", 100));
        var avecStock = notesService.generer(d.id(), ex, NotesService.Decisions.aucune(), d.expert());
        assertThat(note(avecStock, "6").applicability()).isEqualTo("APPLICABLE");
        assertThat(note(avecStock, "6").status()).isEqualTo("INCOMPLETE");        // tableau non genere

        // une validation globale transforme les candidates en NOT_APPLICABLE
        var validee = notesService.generer(d.id(), ex, new NotesService.Decisions(null, Map.of(), true), d.expert());
        assertThat(note(validee, "9").status()).isEqualTo("NOT_APPLICABLE");
        assertThat(note(validee, "16C").applicability()).isEqualTo("TO_DETERMINE");
    }

    @Test
    void findingNoteIncomplete_porteNoteCode_section_et_informationManquante() {
        Dossier d = nouveauDossier(false);
        ecrire(d, LocalDate.now(), m("67", 10), m("521", 10));
        UUID ex = exercice(d);

        var notes = controlPublisher.publierNotes(d.id(), ex, d.expert());

        ControlResult c = notes.controls().stream().filter(x -> x.entityId().endsWith(":29")).findFirst().orElseThrow();
        assertThat(c.code()).isEqualTo(ControlCodes.REQUIRED_NOTE_INCOMPLETE);
        assertThat(c.metadata().get("noteCode")).isEqualTo("29");
        assertThat(c.metadata().get("manques").toString()).contains("section").contains("missingInformation");
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.REQUIRED_NOTE_INCOMPLETE))).isNotEmpty();
    }

    // ------------------------------------------------------------------ snapshot DOSSIER_COMPLET

    @Test
    void snapshotDossierComplet_refuse_siTftIncomplet() {
        Dossier d = nouveauDossier(false);
        ecrire(d, LocalDate.now(), m("521", 700), m("101", 700));            // capital non qualifie : TFT INCOMPLETE
        UUID ex = exercice(d);
        clotureService.cloturerAvecConfirmation(d.id(), ex, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test refus"));
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("TFT INCOMPLETE");
        assertThat(snapshotRepository.findByEntrepriseIdAndExerciceIdOrderByCreatedAtDesc(d.id(), ex)).isEmpty();
    }

    // ------------------------------------------------------------------ multi-tenant

    @Test
    void isolationMultiTenant_drillDownTft_etNotesPubliees() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        UUID exA = exercice(a);
        for (var appel : List.<java.util.function.Supplier<?>>of(
                () -> drillDownService.detailler(b.id(), exA, StatementType.TFT, "FO", null, b.expert()),
                () -> cashFlowStatementService.composants(b.id(), exA, "FO", b.expert()),
                () -> controlPublisher.publierTft(b.id(), exA, b.expert()),
                () -> controlPublisher.publierNotes(b.id(), exA, b.expert()))) {
            assertThatThrownBy(appel::get).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
    }

    // ------------------------------------------------------------------ performance : pas de N+1

    @Test
    void nombreDeRequetes_bilanTftNotes_neDependentPasDuNombreDeComptesNiDeMouvements() {
        Dossier peu = nouveauDossier(false);
        Dossier beaucoup = nouveauDossier(false);
        ecrire(peu, LocalDate.now(), m("521", 100), m("701", 100));

        List<LigneEcritureRequest> lignes = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < 300; i++) {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(beaucoup.id()));
            c.setNumero((i % 2 == 0 ? "7011" : "6011") + String.format("%03d", i));
            c.setIntitule("Compte " + i);
            c.setLettrable(false);
            c = compteComptableRepository.save(c);
            boolean produit = i % 2 == 0;
            lignes.add(new LigneEcritureRequest(c.getId(), "Mouvement " + i, produit ? BigDecimal.ZERO : BigDecimal.TEN, produit ? new BigDecimal(11) : BigDecimal.ZERO, null));
            total += produit ? 11 : -10;
        }
        UUID banque = compteComptableRepository.findByEntrepriseIdAndNumero(beaucoup.id(), "521").orElseThrow().getId();
        lignes.add(new LigneEcritureRequest(banque, "Banque", total > 0 ? new BigDecimal(total) : BigDecimal.ZERO, total < 0 ? new BigDecimal(-total) : BigDecimal.ZERO, null));
        UUID id = ecritureService.creerManuelle(beaucoup.id(), new CreerEcritureRequest(journal(beaucoup), LocalDate.now(), "300 comptes", null, lignes), beaucoup.expert()).id();
        ecritureService.valider(beaucoup.id(), id, beaucoup.expert());

        UUID exPeu = exercice(peu);
        UUID exBeaucoup = exercice(beaucoup);
        var decisions = NotesService.Decisions.aucune();
        // echauffement
        balanceSheetService.bilan(peu.id(), exPeu, peu.expert());
        cashFlowStatementService.generer(peu.id(), exPeu, peu.expert());
        notesService.generer(peu.id(), exPeu, decisions, peu.expert());

        long avant = statements();
        balanceSheetService.bilan(peu.id(), exPeu, peu.expert());
        long bilanPeu = statements() - avant;
        avant = statements();
        cashFlowStatementService.generer(peu.id(), exPeu, peu.expert());
        long tftPeu = statements() - avant;
        avant = statements();
        notesService.generer(peu.id(), exPeu, decisions, peu.expert());
        long notesPeu = statements() - avant;

        avant = statements();
        balanceSheetService.bilan(beaucoup.id(), exBeaucoup, beaucoup.expert());
        long bilanGrand = statements() - avant;
        avant = statements();
        cashFlowStatementService.generer(beaucoup.id(), exBeaucoup, beaucoup.expert());
        long tftGrand = statements() - avant;
        avant = statements();
        notesService.generer(beaucoup.id(), exBeaucoup, decisions, beaucoup.expert());
        long notesGrand = statements() - avant;

        assertThat(bilanGrand).as("Bilan : 300 comptes vs 1 compte").isEqualTo(bilanPeu);
        assertThat(tftGrand).as("TFT : 300 comptes vs 1 compte").isEqualTo(tftPeu);
        assertThat(notesGrand).as("Notes : 300 comptes vs 1 compte").isEqualTo(notesPeu);
    }
}
