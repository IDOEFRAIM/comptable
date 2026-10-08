package com.example.comptable.comptabilite.balance;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.BalanceService;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.etatsfinanciers.service.IncomeStatementService;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.service.GrandLivreService;
import com.example.comptable.comptabilite.web.dto.BalanceDto;
import com.example.comptable.comptabilite.web.dto.BalanceLigneDto;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.GrandLivreDto;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fondation numerique (phase F1.5), sur un vrai PostgreSQL : AccountingBalanceEngine, vues de reporting, nature des ecritures, N / N-1, grand livre et balance
 * coherents, absence de requete par compte, cloture sans effet sur la mesure de l'activite. AUCUNE assertion sur une rubrique reglementaire (BA, CA, TA, XA, ZA...).
 */
class AccountingBalanceEngineTest extends BankTestBase {

    @Autowired AccountingBalanceEngine balanceEngine;
    @Autowired BalanceService balanceService;
    @Autowired GrandLivreService grandLivreService;
    @Autowired EcritureComptableService ecritureService;
    @Autowired FacturationService facturationService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired IncomeStatementService incomeStatementService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    // ---------------------------------------------------------------- outils

    private record Ligne(String compte, String debit, String credit) {
    }

    private UUID compte(Dossier d, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseThrow(() -> new AssertionError("compte absent du PCE de test : " + numero)).getId();
    }

    private UUID journal(Dossier d) {
        return jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
    }

    /** Ecriture manuelle puis validation (jamais un brouillon, sauf {@code valider=false}). */
    private UUID ecriture(Dossier d, LocalDate date, boolean valider, Ligne... lignes) {
        List<LigneEcritureRequest> requetes = new ArrayList<>();
        for (Ligne l : lignes) {
            requetes.add(new LigneEcritureRequest(compte(d, l.compte()), "Ligne " + l.compte(), new BigDecimal(l.debit()), new BigDecimal(l.credit()), null));
        }
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal(d), date, "Test " + date, null, requetes), d.expert()).id();
        if (valider) {
            ecritureService.valider(d.id(), id, d.expert());
        }
        return id;
    }

    private FactureDto vente(Dossier d, TiersDto client, int prixHt) {
        UUID produit = compte(d, "701");
        return facturationService.validerFacture(d.id(), facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client.id(), LocalDate.now().minusDays(2),
                LocalDate.now().plusDays(28), "Vente", List.of(new LigneFactureRequest("Marchandise", BigDecimal.ONE, new BigDecimal(prixHt), BigDecimal.ZERO, null))),
                d.expert()).id(), produit, d.expert());
    }

    private TiersDto client(Dossier d, String nom) {
        return tiersService.creer(d.id(), TypeTiers.CLIENT, nom, null, d.expert());
    }

    private com.example.comptable.comptabilite.domain.ExerciceComptable exerciceCourant(Dossier d) {
        return exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow();
    }

    private Map<String, AccountBalance> parNumero(List<AccountBalance> soldes) {
        return soldes.stream().collect(Collectors.toMap(AccountBalance::accountNumber, Function.identity()));
    }

    private BigDecimal somme(List<AccountBalance> soldes, Function<AccountBalance, BigDecimal> f) {
        return soldes.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void cloturer(Dossier d, UUID exerciceId) {
        clotureService.cloturerAvecConfirmation(d.id(), exerciceId, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test de la fondation numerique"));
    }

    private long statements() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
    }

    // ---------------------------------------------------------------- invariants de base

    @Test
    void capital_la_balance_est_equilibree_et_les_invariants_tiennent() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        ecriture(d, LocalDate.now(), true, new Ligne("521", "100000", "0"), new Ligne("101", "0", "100000"));

        List<AccountBalance> soldes = balanceEngine.balances(d.id(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER);

        assertThat(soldes).extracting(AccountBalance::accountNumber).containsExactly("101", "521");
        // Σ debit periode = Σ credit periode ; ouverture + mouvements = cloture, sur chaque compte
        assertThat(somme(soldes, AccountBalance::periodDebit)).isEqualByComparingTo(somme(soldes, AccountBalance::periodCredit));
        assertThat(somme(soldes, AccountBalance::closingDebit)).isEqualByComparingTo(somme(soldes, AccountBalance::closingCredit));
        for (AccountBalance s : soldes) {
            assertThat(s.openingNet().add(s.periodDebit()).subtract(s.periodCredit())).isEqualByComparingTo(s.netBalance());
        }
        assertThat(parNumero(soldes).get("101").closingCredit()).isEqualByComparingTo("100000");
        assertThat(parNumero(soldes).get("521").closingDebit()).isEqualByComparingTo("100000");
    }

    @Test
    void ouvertureEstLaSommeDeLHistoriqueAnterieur_etLesBornesSontInclusives() {
        Dossier d = nouveauDossier(false);
        LocalDate debut = LocalDate.now().withDayOfMonth(1);
        ecriture(d, debut.minusDays(1), true, new Ligne("521", "1000", "0"), new Ligne("101", "0", "1000"));     // avant : ouverture
        ecriture(d, debut, true, new Ligne("521", "200", "0"), new Ligne("101", "0", "200"));                      // borne de debut incluse
        ecriture(d, LocalDate.now(), true, new Ligne("521", "30", "0"), new Ligne("101", "0", "30"));              // borne de fin incluse
        ecriture(d, LocalDate.now().plusDays(1), true, new Ligne("521", "4", "0"), new Ligne("101", "0", "4"));    // apres la fin : hors perimetre

        AccountBalance banque = balanceEngine.balance(d.id(), compte(d, "521"), debut, LocalDate.now(), ReportingView.LEGAL_LEDGER).orElseThrow();

        assertThat(banque.openingNet()).isEqualByComparingTo("1000");
        assertThat(banque.periodDebit()).isEqualByComparingTo("230");
        assertThat(banque.netBalance()).isEqualByComparingTo("1230");
    }

    @Test
    void brouillon_exclu_du_legal_inclus_dans_la_vue_de_travail() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        ecriture(d, LocalDate.now(), true, new Ligne("521", "500", "0"), new Ligne("101", "0", "500"));
        ecriture(d, LocalDate.now(), false, new Ligne("521", "70", "0"), new Ligne("101", "0", "70"));

        BigDecimal legal = balanceEngine.balance(d.id(), compte(d, "521"), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER).orElseThrow().netBalance();
        BigDecimal travail = balanceEngine.balance(d.id(), compte(d, "521"), ex.getDateDebut(), ex.getDateFin(), ReportingView.WORKING_INCLUDING_DRAFTS).orElseThrow().netBalance();

        assertThat(legal).isEqualByComparingTo("500");
        assertThat(travail).isEqualByComparingTo("570");
        assertThat(ReportingView.LEGAL_LEDGER.officielle()).isTrue();
        assertThat(ReportingView.WORKING_INCLUDING_DRAFTS.officielle()).isFalse();
    }

    @Test
    void periodeInvalide_est_refusee() {
        Dossier d = nouveauDossier(false);
        try {
            balanceEngine.balances(d.id(), LocalDate.now(), LocalDate.now().minusDays(1), ReportingView.LEGAL_LEDGER);
            throw new AssertionError("une periode inversee aurait du etre refusee");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(422);
        }
    }

    // ---------------------------------------------------------------- operations metier

    @Test
    void vente_et_achat_apparaissent_dans_la_balance_et_le_grand_livre_avec_le_meme_solde() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        FactureDto f = vente(d, client(d, "Client Vente"), 100_000);
        acheterSansPiece(d, 40_000);

        List<AccountBalance> soldes = balanceEngine.balances(d.id(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER);
        Map<String, AccountBalance> m = parNumero(soldes);
        assertThat(f.ecritureId()).isNotNull();
        assertThat(m.get("701").closingCredit()).isEqualByComparingTo("100000");
        assertThat(m.get("601").closingDebit()).isEqualByComparingTo("40000");
        assertThat(m.keySet()).anyMatch(n -> n.startsWith("41"));
        assertThat(m.keySet()).anyMatch(n -> n.startsWith("40"));
        assertThat(somme(soldes, AccountBalance::closingDebit)).isEqualByComparingTo(somme(soldes, AccountBalance::closingCredit));

        // Grand livre : le solde final de chaque compte est celui du moteur ; la balance generale est le meme calcul
        for (AccountBalance s : soldes) {
            GrandLivreDto gl = grandLivreService.generer(d.id(), s.accountId(), ex.getDateDebut(), ex.getDateFin(), d.expert());
            assertThat(gl.soldeCloture().debit()).as("GL debit %s", s.accountNumber()).isEqualByComparingTo(s.closingDebit());
            assertThat(gl.soldeCloture().credit()).as("GL credit %s", s.accountNumber()).isEqualByComparingTo(s.closingCredit());
            if (!gl.lignes().isEmpty()) {
                var derniere = gl.lignes().get(gl.lignes().size() - 1).soldeProgressif();
                assertThat(derniere.debit()).isEqualByComparingTo(gl.soldeCloture().debit());
                assertThat(derniere.credit()).isEqualByComparingTo(gl.soldeCloture().credit());
            }
        }
        BalanceDto balance = balanceService.generer(d.id(), ex.getDateDebut(), ex.getDateFin(), d.expert());
        assertThat(balance.totalMouvementDebit()).isEqualByComparingTo(balance.totalMouvementCredit());
        assertThat(balance.totalSoldeClotureDebit()).isEqualByComparingTo(balance.totalSoldeClotureCredit());
        assertThat(balance.lignes()).extracting(BalanceLigneDto::compteNumero).isSorted();
    }

    @Test
    void amortissement_dotation_par_le_moteur_est_un_ajustement_present_dans_l_activite() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        UUID immo = creerImmobilisationEnService(d);
        assertThat(immo).isNotNull();

        List<AccountBalance> activite = balanceEngine.balances(d.id(), ex.getDateDebut(), ex.getDateFin(), ReportingView.PERIOD_ACTIVITY);
        Map<String, AccountBalance> m = parNumero(activite);
        assertThat(m.get("281").closingCredit()).isEqualByComparingTo("1000000");
        assertThat(m.keySet()).anyMatch(n -> n.startsWith("68"));
        assertThat(jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ? and nature = 'ADJUSTMENT'", Long.class, d.id())).isEqualTo(1L);
    }

    // ---------------------------------------------------------------- cloture : le bug CR = 0

    @Test
    void cloture_la_mesure_de_l_activite_reste_correcte_et_le_grand_livre_legal_contient_la_cloture() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        vente(d, client(d, "Client Cloture"), 1_000_000);

        BigDecimal avant = balanceEngine.balance(d.id(), compte(d, "701"), ex.getDateDebut(), ex.getDateFin(), ReportingView.PERIOD_ACTIVITY).orElseThrow().periodNet();
        assertThat(avant).isEqualByComparingTo("-1000000");

        cloturer(d, ex.getId());

        // Activite economique de N : INCHANGEE (la cloture est une ecriture technique, nature CLOSING)
        BigDecimal apres = balanceEngine.balance(d.id(), compte(d, "701"), ex.getDateDebut(), ex.getDateFin(), ReportingView.PERIOD_ACTIVITY).orElseThrow().periodNet();
        assertThat(apres).isEqualByComparingTo("-1000000");
        assertThat(jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ? and nature = 'CLOSING'", Long.class, d.id())).isEqualTo(1L);

        // Grand livre LEGAL : l'ecriture de cloture est bien la, le compte de produit est solde
        AccountBalance legal = balanceEngine.balance(d.id(), compte(d, "701"), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER).orElseThrow();
        assertThat(legal.netBalance()).isEqualByComparingTo("0");
        assertThat(legal.periodDebit()).isEqualByComparingTo("1000000");
        GrandLivreDto gl = grandLivreService.generer(d.id(), compte(d, "701"), ex.getDateDebut(), ex.getDateFin(), d.expert());
        assertThat(gl.lignes()).hasSize(2);

        // Le compte de resultat reglementaire (XI) ne tombe pas a zero apres la cloture
        assertThat(incomeStatementService.generer(d.id(), ex.getId(), d.expert()).lines().stream().filter(l -> l.code().equals("XI")).findFirst().orElseThrow().amountN())
                .isEqualByComparingTo("1000000");
    }

    @Test
    void cloture_le_bilan_de_cloture_N_est_l_ouverture_de_N_plus_1_et_la_gestion_repart_de_zero() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        ecriture(d, LocalDate.now(), true, new Ligne("521", "100000", "0"), new Ligne("101", "0", "100000"));
        vente(d, client(d, "Client Report"), 250_000);
        cloturer(d, ex.getId());

        var suivant = exerciceRepository.findByEntrepriseIdAndDate(d.id(), ex.getDateFin().plusDays(1)).orElseThrow();
        Map<String, AccountBalance> clotureN = parNumero(balanceEngine.balances(d.id(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER));
        Map<String, AccountBalance> ouvertureN1 = parNumero(balanceEngine.balances(d.id(), suivant.getDateDebut(), suivant.getDateFin(), ReportingView.LEGAL_LEDGER));

        // Aucune ecriture en N+1 : les comptes de bilan ressortent avec leur seule OUVERTURE, egale a la cloture de N (continuite, art. 34 au niveau des comptes).
        for (String numero : List.of("101", "521")) {
            assertThat(ouvertureN1).containsKey(numero);
            assertThat(ouvertureN1.get(numero).openingNet()).as("ouverture N+1 de %s", numero).isEqualByComparingTo(clotureN.get(numero).netBalance());
            assertThat(ouvertureN1.get(numero).periodDebit().add(ouvertureN1.get(numero).periodCredit())).isEqualByComparingTo("0");
        }
        // Comptes de gestion : solde par la cloture, donc ouverture N+1 nulle
        assertThat(ouvertureN1.get("701").openingNet()).isEqualByComparingTo("0");
        // Comptes de gestion : soldes par la cloture, ouverture N+1 nulle
        assertThat(clotureN.get("701").netBalance()).isEqualByComparingTo("0");
        assertThat(clotureN.values().stream().filter(s -> s.accountNumber().startsWith("6") || s.accountNumber().startsWith("7")))
                .allSatisfy(s -> assertThat(s.netBalance()).isEqualByComparingTo("0"));
        // Partie double sur l'ensemble : la somme des soldes de cloture N est nulle
        assertThat(somme(new ArrayList<>(clotureN.values()), AccountBalance::netBalance)).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- N et N-1

    @Test
    void comparatifN_moins_1_utilise_l_exercice_precedent_reel_meme_irregulier() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        // Exercice precedent de 18 mois (pas « date moins un an »), ouvert pour y poster
        LocalDate debutPrecedent = ex.getDateDebut().minusMonths(18);
        LocalDate finPrecedent = ex.getDateDebut().minusDays(1);
        jdbc.update("insert into exercice_comptable (entreprise_id, date_debut, date_fin, statut) values (?, ?, ?, 'OUVERT')", d.id(), debutPrecedent, finPrecedent);
        ecriture(d, debutPrecedent.plusDays(3), true, new Ligne("521", "700", "0"), new Ligne("101", "0", "700"));
        ecriture(d, finPrecedent.minusDays(3), true, new Ligne("521", "50", "0"), new Ligne("101", "0", "50"));
        ecriture(d, LocalDate.now(), true, new Ligne("521", "9", "0"), new Ligne("101", "0", "9"));

        ExerciseBalances comp = balanceEngine.avecComparatif(d.id(), ex.getId(), ReportingView.LEGAL_LEDGER);

        assertThat(comp.previousExercice()).isPresent();
        assertThat(comp.previousExercice().orElseThrow().getDateDebut()).isEqualTo(debutPrecedent);
        assertThat(comp.previousExercice().orElseThrow().getDateFin()).isEqualTo(finPrecedent);
        AccountBalance banqueN = parNumero(comp.current()).get("521");
        AccountBalance banqueN1 = parNumero(comp.previous()).get("521");
        assertThat(banqueN.periodDebit()).isEqualByComparingTo("9");
        assertThat(banqueN.openingNet()).isEqualByComparingTo("750");     // ouverture N = cloture N-1
        assertThat(banqueN1.periodDebit()).isEqualByComparingTo("750");
        assertThat(banqueN1.openingNet()).isEqualByComparingTo("0");
        assertThat(banqueN1.netBalance()).isEqualByComparingTo(banqueN.openingNet());
    }

    @Test
    void premierExercice_sans_precedent_a_un_comparatif_vide() {
        Dossier d = nouveauDossier(false);
        ExerciseBalances comp = balanceEngine.avecComparatif(d.id(), exerciceCourant(d).getId(), ReportingView.LEGAL_LEDGER);
        assertThat(comp.previousExercice()).isEmpty();
        assertThat(comp.previous()).isEmpty();
    }

    // ---------------------------------------------------------------- annulation de facture

    @Test
    void annulationDeFacture_contrepasse_l_ecriture_reste_auditable_et_est_idempotente() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        FactureDto f = vente(d, client(d, "Client Annule"), 80_000);
        assertThat(balanceEngine.balance(d.id(), compte(d, "701"), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER).orElseThrow().netBalance())
                .isEqualByComparingTo("-80000");

        FactureDto annulee = facturationService.annulerFacture(d.id(), f.id(), d.expert());

        assertThat(annulee.statut()).isEqualTo("ANNULEE");
        // Conséquence inverse : plus aucun solde sur les comptes de la vente (balance nette coherente), mais les DEUX ecritures restent au grand livre
        List<AccountBalance> soldes = balanceEngine.balances(d.id(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER);
        assertThat(parNumero(soldes).get("701").netBalance()).isEqualByComparingTo("0");
        assertThat(parNumero(soldes).get("701").periodCredit()).isEqualByComparingTo("80000");
        assertThat(parNumero(soldes).get("701").periodDebit()).isEqualByComparingTo("80000");
        assertThat(soldes.stream().filter(s -> s.accountNumber().startsWith("41"))).allSatisfy(s -> assertThat(s.netBalance()).isEqualByComparingTo("0"));
        // Lineage : facture -> ecriture de validation ; facture -> ecriture d'annulation (deux propositions, deux ecritures)
        assertThat(jdbc.queryForObject("select count(*) from accounting_operation_proposal where entreprise_id = ? and source_type = 'FACTURE' and source_id = ? and ecriture_id is not null",
                Long.class, d.id(), f.id())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from accounting_operation_proposal where entreprise_id = ? and source_id = ? and type = 'ANNULATION_FACTURE'",
                Long.class, d.id(), f.id())).isEqualTo(1L);

        // Idempotence : une seconde annulation est refusee (409) et ne produit AUCUNE seconde contrepassation
        try {
            facturationService.annulerFacture(d.id(), f.id(), d.expert());
            throw new AssertionError("la seconde annulation aurait du etre refusee");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("select count(*) from accounting_operation_proposal where entreprise_id = ? and source_id = ? and type = 'ANNULATION_FACTURE'",
                Long.class, d.id(), f.id())).isEqualTo(1L);
        assertThat(parNumero(balanceEngine.balances(d.id(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER)).get("701").netBalance()).isEqualByComparingTo("0");
    }

    @Autowired com.example.comptable.stock.service.ArticleService articleService;
    @Autowired com.example.comptable.stock.service.MouvementStockService mouvementStockService;

    @Test
    void annulationDeFactureAvecStock_facture_stock_et_comptabilite_restent_coherents() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        var article = articleService.creer(d.id(), new com.example.comptable.stock.web.dto.CreerArticleRequest("ART-ANN", "Ciment", null,
                com.example.comptable.stock.domain.TypeArticle.MARCHANDISE, "sac", new BigDecimal("6500"), new BigDecimal("18"),
                compte(d, "311"), compte(d, "601"), compte(d, "701"), compte(d, "6031"), null, null, null), d.expert());
        mouvementStockService.enregistrer(d.id(), new com.example.comptable.stock.web.dto.CreerMouvementStockRequest(article.id(),
                com.example.comptable.stock.domain.TypeMouvementStock.ENTREE, new BigDecimal("20"), new BigDecimal("5000"), LocalDate.now(), "Entree"), d.expert());
        BigDecimal stockInitial = balanceEngine.balance(d.id(), compte(d, "311"), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER).orElseThrow().netBalance();
        assertThat(stockInitial).isEqualByComparingTo("100000");

        TiersDto client = client(d, "Client Stock");
        FactureDto f = facturationService.validerFacture(d.id(), facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client.id(), LocalDate.now(),
                LocalDate.now().plusDays(30), "Vente stock", List.of(new LigneFactureRequest("Ciment", new BigDecimal("5"), new BigDecimal("6500"), BigDecimal.ZERO, article.id()))),
                d.expert()).id(), compte(d, "701"), d.expert());
        assertThat(balanceEngine.balance(d.id(), compte(d, "311"), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER).orElseThrow().netBalance())
                .isEqualByComparingTo("75000");   // 5 sacs sortis au CUMP de 5 000

        facturationService.annulerFacture(d.id(), f.id(), d.expert());

        List<AccountBalance> soldes = balanceEngine.balances(d.id(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER);
        Map<String, AccountBalance> m = parNumero(soldes);
        assertThat(m.get("311").netBalance()).as("stock comptable revenu a sa valeur d'avant la vente").isEqualByComparingTo("100000");
        assertThat(m.get("701").netBalance()).isEqualByComparingTo("0");
        assertThat(soldes.stream().filter(s -> s.accountNumber().startsWith("41"))).allSatisfy(s -> assertThat(s.netBalance()).isEqualByComparingTo("0"));
        assertThat(somme(soldes, AccountBalance::closingDebit)).isEqualByComparingTo(somme(soldes, AccountBalance::closingCredit));
    }

    // ---------------------------------------------------------------- isolation

    @Test
    void multiTenant_une_entreprise_ne_voit_ni_comptes_ni_ecritures_ni_soldes_d_une_autre() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        var ex = exerciceCourant(a);
        ecriture(a, LocalDate.now(), true, new Ligne("521", "12345", "0"), new Ligne("101", "0", "12345"));

        assertThat(balanceEngine.balances(b.id(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER)).isEmpty();
        assertThat(balanceEngine.balance(b.id(), compte(a, "521"), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER)).isEmpty();
        try {
            balanceEngine.balancesExercice(b.id(), ex.getId(), ReportingView.LEGAL_LEDGER);
            throw new AssertionError("l'exercice d'une autre entreprise aurait du etre introuvable");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(404);
        }
        try {
            grandLivreService.generer(b.id(), compte(a, "521"), ex.getDateDebut(), ex.getDateFin(), b.expert());
            throw new AssertionError("le compte d'une autre entreprise aurait du etre introuvable");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ---------------------------------------------------------------- performance (nombre de requetes, pas de duree)

    @Test
    void aucuneRequeteParCompte_le_nombre_de_requetes_ne_depend_pas_du_nombre_de_comptes() {
        Dossier peu = nouveauDossier(false);
        Dossier beaucoup = nouveauDossier(false);
        var exPeu = exerciceCourant(peu);
        var exBeaucoup = exerciceCourant(beaucoup);
        ecriture(peu, LocalDate.now(), true, new Ligne("521", "10", "0"), new Ligne("101", "0", "10"));

        // 60 comptes locaux mouvementes dans l'autre entreprise
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 60; i++) {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(beaucoup.id()));
            c.setNumero("6199" + String.format("%02d", i));
            c.setIntitule("Compte de test " + i);
            c.setLettrable(false);
            c = compteComptableRepository.save(c);
            lignes.add(new LigneEcritureRequest(c.getId(), "Debit " + i, new BigDecimal("10"), BigDecimal.ZERO, null));
            total = total.add(new BigDecimal("10"));
        }
        lignes.add(new LigneEcritureRequest(compte(beaucoup, "521"), "Contrepartie", BigDecimal.ZERO, total, null));
        UUID id = ecritureService.creerManuelle(beaucoup.id(), new CreerEcritureRequest(journal(beaucoup), LocalDate.now(), "60 comptes", null, lignes), beaucoup.expert()).id();
        ecritureService.valider(beaucoup.id(), id, beaucoup.expert());

        balanceService.generer(peu.id(), exPeu.getDateDebut(), exPeu.getDateFin(), peu.expert());   // echauffement
        long avant = statements();
        BalanceDto petite = balanceService.generer(peu.id(), exPeu.getDateDebut(), exPeu.getDateFin(), peu.expert());
        long requetesPetite = statements() - avant;
        avant = statements();
        BalanceDto grande = balanceService.generer(beaucoup.id(), exBeaucoup.getDateDebut(), exBeaucoup.getDateFin(), beaucoup.expert());
        long requetesGrande = statements() - avant;

        assertThat(petite.lignes()).hasSize(2);
        assertThat(grande.lignes()).hasSize(61);
        assertThat(requetesGrande).as("requetes pour 61 comptes vs 2 comptes").isEqualTo(requetesPetite);
        assertThat(requetesGrande).isLessThanOrEqualTo(6);
    }

    // ---------------------------------------------------------------- empreinte technique

    @Test
    void empreinteTechnique_est_stable_sur_des_donnees_identiques_et_change_a_chaque_mutation() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceCourant(d);
        ecriture(d, LocalDate.now(), true, new Ligne("521", "100", "0"), new Ligne("101", "0", "100"));

        DatasetVersion v1 = balanceEngine.datasetVersion(d.id(), ex.getId(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER);
        DatasetVersion v1bis = balanceEngine.datasetVersion(d.id(), ex.getId(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER);
        assertThat(v1.fingerprint()).isEqualTo(v1bis.fingerprint()).hasSize(64);
        assertThat(v1.entryCount()).isEqualTo(1);
        assertThat(v1.lineCount()).isEqualTo(2);
        assertThat(v1.totalDebit()).isEqualByComparingTo("100");

        ecriture(d, LocalDate.now(), false, new Ligne("521", "5", "0"), new Ligne("101", "0", "5"));   // brouillon : hors perimetre legal
        assertThat(balanceEngine.datasetVersion(d.id(), ex.getId(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER).fingerprint()).isEqualTo(v1.fingerprint());

        ecriture(d, LocalDate.now(), true, new Ligne("521", "7", "0"), new Ligne("101", "0", "7"));
        DatasetVersion v2 = balanceEngine.datasetVersion(d.id(), ex.getId(), ex.getDateDebut(), ex.getDateFin(), ReportingView.LEGAL_LEDGER);
        assertThat(v2.fingerprint()).isNotEqualTo(v1.fingerprint());
        assertThat(v2.entryCount()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- outils immobilisation

    @Autowired private com.example.comptable.comptabilite.service.ImmobilisationService immobilisationServiceBean;

    private com.example.comptable.comptabilite.service.ImmobilisationService immobilisationService() {
        return immobilisationServiceBean;
    }

    private UUID creerImmobilisationEnService(Dossier d) {
        var service = immobilisationService();
        var immo = service.creer(d.id(), new com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest(compte(d, "244"), compte(d, "281"), "Camion",
                com.example.comptable.comptabilite.domain.TypeImmobilisation.CORPORELLE, LocalDate.now().withDayOfYear(1), new BigDecimal("5000000"), 5,
                com.example.comptable.comptabilite.domain.ModeAmortissement.LINEAIRE), d.expert());
        immo = service.comptabiliserAcquisition(d.id(), immo.id(),
                new com.example.comptable.comptabilite.web.dto.ComptabiliserAcquisitionRequest(d.fournisseur().id(), BigDecimal.ZERO, null), d.expert());
        immo = service.mettreEnService(d.id(), immo.id(), new com.example.comptable.comptabilite.web.dto.MettreEnServiceRequest(LocalDate.now().withDayOfYear(1)), d.expert());
        var resultat = service.genererDotations(d.id(), LocalDate.now().getYear(), d.expert());
        assertThat(resultat.totalDotations()).isEqualByComparingTo("1000000");
        return immo.id();
    }
}
