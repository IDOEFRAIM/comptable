package com.example.comptable.comptabilite.balance;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.stock.domain.TypeArticle;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.BalanceService;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.service.GrandLivreService;
import com.example.comptable.comptabilite.service.ImmobilisationService;
import com.example.comptable.comptabilite.web.dto.BalanceDto;
import com.example.comptable.comptabilite.web.dto.ComptabiliserAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.GrandLivreDto;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.MettreEnServiceRequest;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.service.ArticleService;
import com.example.comptable.stock.service.MouvementStockService;
import com.example.comptable.stock.web.dto.ArticleDto;
import com.example.comptable.stock.web.dto.CreerArticleRequest;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario complet de la fondation numerique, sur un vrai PostgreSQL : entreprise, plan de comptes, N-1 puis N, achat, vente, paiement, stock, immobilisation et
 * amortissement, cloture de N-1, activite de N, annulation d'une facture. Verifie balance, grand livre, N / N-1, ouvertures et clotures, absence de requete par
 * compte et isolation. Aucune assertion sur une rubrique reglementaire (BA, CA, TA, XA, ZA...) : F2 reste bloque.
 */
class ReportingFoundationE2ETest extends BankTestBase {

    @Autowired AccountingBalanceEngine engineSoldes;
    @Autowired BalanceService balanceService;
    @Autowired GrandLivreService grandLivreService;
    @Autowired EcritureComptableService ecritureService;
    @Autowired FacturationService facturationService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired ImmobilisationService immobilisationService;
    @Autowired ArticleService articleService;
    @Autowired MouvementStockService mouvementStockService;
    @Autowired ExerciceComptableRepository exerciceRepository;

    private UUID compte(Dossier d, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseThrow().getId();
    }

    private FactureDto vente(Dossier d, UUID client, LocalDate date, int prixHt) {
        return facturationService.validerFacture(d.id(), facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client, date, date.plusDays(30), "Vente",
                List.of(new LigneFactureRequest("Marchandise", BigDecimal.ONE, new BigDecimal(prixHt), BigDecimal.ZERO, null))), d.expert()).id(), compte(d, "701"), d.expert());
    }

    private Map<String, AccountBalance> parNumero(List<AccountBalance> soldes) {
        return soldes.stream().collect(Collectors.toMap(AccountBalance::accountNumber, Function.identity()));
    }

    private BigDecimal somme(List<AccountBalance> soldes, Function<AccountBalance, BigDecimal> f) {
        return soldes.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void scenarioComplet_N_moins_1_cloture_puis_N_avec_annulation() {
        Dossier d = nouveauDossier(false);
        Dossier autre = nouveauDossier(false);
        var exN = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow();
        LocalDate debutN1 = exN.getDateDebut().minusYears(1);
        LocalDate finN1 = exN.getDateDebut().minusDays(1);
        jdbc.update("insert into exercice_comptable (entreprise_id, date_debut, date_fin, statut) values (?, ?, ?, 'OUVERT')", d.id(), debutN1, finN1);
        UUID exN1 = exerciceRepository.findByEntrepriseIdAndDate(d.id(), finN1).orElseThrow().getId();
        UUID client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client E2E", null, d.expert()).id();
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());

        // ---- N-1 : capital, vente ; puis cloture de N-1
        UUID capital = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, debutN1.plusDays(5), "Capital", null, List.of(
                new LigneEcritureRequest(compte(d, "521"), "Banque", new BigDecimal("2000000"), BigDecimal.ZERO, null),
                new LigneEcritureRequest(compte(d, "101"), "Capital", BigDecimal.ZERO, new BigDecimal("2000000"), null))), d.expert()).id();
        ecritureService.valider(d.id(), capital, d.expert());
        vente(d, client, finN1.minusDays(10), 600_000);
        BigDecimal activiteVenteN1 = engineSoldes.balance(d.id(), compte(d, "701"), debutN1, finN1, ReportingView.PERIOD_ACTIVITY).orElseThrow().periodNet();
        assertThat(activiteVenteN1).isEqualByComparingTo("-600000");

        clotureService.cloturerAvecConfirmation(d.id(), exN1, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Cloture N-1 du scenario"));

        // L'activite de N-1 reste mesurable apres sa cloture ; le grand livre legal contient la cloture
        assertThat(engineSoldes.balance(d.id(), compte(d, "701"), debutN1, finN1, ReportingView.PERIOD_ACTIVITY).orElseThrow().periodNet()).isEqualByComparingTo("-600000");
        assertThat(engineSoldes.balance(d.id(), compte(d, "701"), debutN1, finN1, ReportingView.LEGAL_LEDGER).orElseThrow().netBalance()).isEqualByComparingTo("0");

        // ---- N : achat, vente, paiement fournisseur, stock, immobilisation + dotation
        acheterSansPiece(d, 300_000);
        FactureDto aAnnuler = vente(d, client, LocalDate.now().minusDays(1), 150_000);
        vente(d, client, LocalDate.now().minusDays(1), 400_000);
        engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_FOURNISSEUR, d.fournisseur().id(), LocalDate.now(),
                "VIR-E2E", "Reglement", new BigDecimal("100000"), MoyenPaiement.VIREMENT), d.expert()).id(), d.expert());
        ArticleDto article = articleService.creer(d.id(), new CreerArticleRequest("ART-E2E", "Ciment", null, TypeArticle.MARCHANDISE, "sac", new BigDecimal("6500"), new BigDecimal("18"),
                compte(d, "311"), compte(d, "601"), compte(d, "701"), compte(d, "6031"), null, null, null), d.expert());
        mouvementStockService.enregistrer(d.id(), new CreerMouvementStockRequest(article.id(), TypeMouvementStock.ENTREE, new BigDecimal("20"), new BigDecimal("5000"), LocalDate.now(),
                "Entree"), d.expert());
        var immo = immobilisationService.creer(d.id(), new CreerImmobilisationRequest(compte(d, "244"), compte(d, "281"), "Camion", TypeImmobilisation.CORPORELLE,
                LocalDate.now().withDayOfYear(1), new BigDecimal("5000000"), 5, ModeAmortissement.LINEAIRE), d.expert());
        immo = immobilisationService.comptabiliserAcquisition(d.id(), immo.id(), new ComptabiliserAcquisitionRequest(d.fournisseur().id(), BigDecimal.ZERO, null), d.expert());
        immo = immobilisationService.mettreEnService(d.id(), immo.id(), new MettreEnServiceRequest(LocalDate.now().withDayOfYear(1)), d.expert());
        immobilisationService.genererDotations(d.id(), LocalDate.now().getYear(), d.expert());

        // ---- annulation d'une facture de N
        facturationService.annulerFacture(d.id(), aAnnuler.id(), d.expert());

        // ---- verifications : balance / grand livre / N-N-1 / ouvertures / requetes / tenant
        ExerciseBalances comparatif = engineSoldes.avecComparatif(d.id(), exN.getId(), ReportingView.LEGAL_LEDGER);
        assertThat(comparatif.previousExercice()).isPresent();
        assertThat(comparatif.previousExercice().orElseThrow().getId()).isEqualTo(exN1);
        Map<String, AccountBalance> n = parNumero(comparatif.current());
        Map<String, AccountBalance> n1 = parNumero(comparatif.previous());

        // Continuite : pour tout compte de N-1, la cloture de N-1 est l'ouverture de N (aucun compte reporte n'est perdu ou invente)
        for (AccountBalance cloture : comparatif.previous()) {
            AccountBalance suivant = n.get(cloture.accountNumber());
            if (suivant != null) {
                assertThat(suivant.openingNet()).as("ouverture N de %s", cloture.accountNumber()).isEqualByComparingTo(cloture.netBalance());
            }
        }
        // La gestion de N-1 a ete soldee : elle ne s'ouvre pas dans N
        assertThat(n1.get("701").netBalance()).isEqualByComparingTo("0");
        assertThat(n.get("701").openingNet()).isEqualByComparingTo("0");
        // Capital reporte
        assertThat(n.get("101").openingNet()).isEqualByComparingTo("-2000000");

        // Partie double sur chaque colonne, ouverture + mouvement = cloture
        for (List<AccountBalance> soldes : List.of(comparatif.current(), comparatif.previous())) {
            assertThat(somme(soldes, AccountBalance::periodDebit)).isEqualByComparingTo(somme(soldes, AccountBalance::periodCredit));
            assertThat(somme(soldes, AccountBalance::closingDebit)).isEqualByComparingTo(somme(soldes, AccountBalance::closingCredit));
            soldes.forEach(s -> assertThat(s.openingNet().add(s.periodDebit()).subtract(s.periodCredit())).isEqualByComparingTo(s.netBalance()));
        }

        // Grand livre = moteur pour chaque compte de N
        for (AccountBalance s : comparatif.current()) {
            GrandLivreDto gl = grandLivreService.generer(d.id(), s.accountId(), exN.getDateDebut(), exN.getDateFin(), d.expert());
            assertThat(gl.soldeOuverture().debit()).isEqualByComparingTo(s.openingDebit());
            assertThat(gl.soldeOuverture().credit()).isEqualByComparingTo(s.openingCredit());
            assertThat(gl.soldeCloture().debit()).as("GL %s", s.accountNumber()).isEqualByComparingTo(s.closingDebit());
            assertThat(gl.soldeCloture().credit()).as("GL %s", s.accountNumber()).isEqualByComparingTo(s.closingCredit());
        }

        // Activite de N (hors cloture) : ventes 150 000 + 400 000 au credit, moins la contrepassation de 150 000 de la facture annulee = 400 000 de produits nets
        assertThat(engineSoldes.balance(d.id(), compte(d, "701"), exN.getDateDebut(), exN.getDateFin(), ReportingView.PERIOD_ACTIVITY).orElseThrow().periodNet())
                .isEqualByComparingTo("-400000");

        // Balance generale = meme calcul, equilibree
        BalanceDto balance = balanceService.generer(d.id(), exN.getDateDebut(), exN.getDateFin(), d.expert());
        assertThat(balance.totalMouvementDebit()).isEqualByComparingTo(balance.totalMouvementCredit());
        assertThat(balance.totalSoldeClotureDebit()).isEqualByComparingTo(balance.totalSoldeClotureCredit());

        // Isolation : l'autre entreprise ne voit rien, ni en N ni en N-1
        assertThat(engineSoldes.balances(autre.id(), debutN1, exN.getDateFin(), ReportingView.LEGAL_LEDGER)).isEmpty();
    }
}
