package com.example.comptable.comptabilite.allocation;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.dossier.DossierComptableService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le recouvrement lit le statut de paiement CALCULE (montant - allocations actives), plus un drapeau saisi : une facture echue est « en retard » tant qu'il
 * reste a payer, et cesse de l'etre quand les allocations la soldent. Un paiement partiel laisse le reste en retard (jamais « payee »).
 */
class RecouvrementStatutCalculeTest extends BankTestBase {

    @Autowired FacturationService facturationService;
    @Autowired FactureRepository factureRepository;
    @Autowired StatutPaiementFactureService statutService;
    @Autowired PaymentAllocationService allocationService;
    @Autowired PaiementFactureEnrichisseur enrichisseur;
    @Autowired DossierComptableService dossierService;

    private FactureDto factureEchue(Dossier d, TiersDto client, int prixHt) {
        UUID produit = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "701").orElseThrow().getId();
        FactureDto brouillon = facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client.id(), LocalDate.now().minusDays(60),
                LocalDate.now().minusDays(30), "Vente echue", List.of(new LigneFactureRequest("Marchandise", BigDecimal.ONE, new BigDecimal(prixHt), BigDecimal.ZERO, null))),
                d.expert());
        return facturationService.validerFacture(d.id(), brouillon.id(), produit, d.expert());
    }

    private UUID ligneClient(UUID ecritureId, String cote) {
        return jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' and l."
                + cote + " > 0", UUID.class, ecritureId);
    }

    private UUID encaissement(Dossier d, TiersDto client, int montant) {
        UUID ecriture = engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT,
                client.id(), LocalDate.now(), "ENC-" + UUID.randomUUID().toString().substring(0, 5), "Encaissement", new BigDecimal(montant),
                MoyenPaiement.VIREMENT), d.expert()).id(), d.expert()).ecritureId();
        return ligneClient(ecriture, "credit");
    }

    private int scoreClients(Dossier d) {
        return dossierService.generer(d.id(), d.expert()).scoresParDomaine().stream().filter(x -> "Clients".equals(x.domaine())).findFirst().orElseThrow().score();
    }

    private long retards(Dossier d) {
        var factures = factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(d.id(), TypeFacture.FACTURE,
                StatutFacture.VALIDEE, LocalDate.now());
        return statutService.ouvertes(d.id(), factures).size();
    }

    @Test
    void factureEchue_estEnRetardTantQuIlResteAPayer_puisPlusDuTout() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Delta", null, d.expert());
        FactureDto facture = factureEchue(d, client, 1000);
        UUID ligneFacture = ligneClient(facture.ecritureId(), "debit");
        int ttc = jdbc.queryForObject("select debit from ligne_ecriture where id = ?", BigDecimal.class, ligneFacture).intValue();
        assertThat(retards(d)).isEqualTo(1);

        // Paiement partiel : le reste demeure en retard, la facture n'est jamais « payee ».
        UUID acompte = encaissement(d, client, 600);
        allocationService.allouer(d.id(), acompte, ligneFacture, new BigDecimal("600"), SourceAllocation.USER, d.expert());
        assertThat(retards(d)).isEqualTo(1);
        var ouverte = statutService.ouvertes(d.id(), List.of(factureRepository.findById(facture.id()).orElseThrow())).get(0);
        assertThat(ouverte.reste()).isEqualByComparingTo(String.valueOf(ttc - 600));
        assertThat(ouverte.statut()).isEqualTo(StatutPaiement.PARTIELLEMENT_PAYEE);

        // Solde : plus aucun retard, et le statut stocke n'a JAMAIS ete modifie a la main.
        UUID solde = encaissement(d, client, ttc - 600);
        allocationService.allouer(d.id(), solde, ligneFacture, new BigDecimal(ttc - 600), SourceAllocation.USER, d.expert());
        assertThat(retards(d)).isZero();
        assertThat(statutService.payees(d.id(), List.of(factureRepository.findById(facture.id()).orElseThrow()))).hasSize(1);
        assertThat(factureRepository.findById(facture.id()).orElseThrow().getStatut()).isEqualTo(StatutFacture.VALIDEE);

        // Annuler l'affectation du solde : la facture redevient en retard pour ce reste.
        var allocation = allocationService.lister(d.id(), d.expert()).stream().filter(a -> a.getAllocatedAmount().intValue() == ttc - 600).findFirst().orElseThrow();
        allocationService.annuler(d.id(), allocation.getId(), "Erreur d'affectation", d.expert());
        assertThat(retards(d)).isEqualTo(1);
    }

    @Test
    void leScoreClientsDuDossier_suitLeResteAPayer() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Epsilon", null, d.expert());
        FactureDto facture = factureEchue(d, client, 500);
        UUID ligneFacture = ligneClient(facture.ecritureId(), "debit");
        int ttc = jdbc.queryForObject("select debit from ligne_ecriture where id = ?", BigDecimal.class, ligneFacture).intValue();
        int scoreAvant = scoreClients(d);

        allocationService.allouer(d.id(), encaissement(d, client, ttc), ligneFacture, new BigDecimal(ttc), SourceAllocation.USER, d.expert());

        int scoreApres = scoreClients(d);
        assertThat(scoreAvant).isLessThan(100);
        assertThat(scoreApres).isEqualTo(100);
    }

    @Test
    void uneFactureNonComptabilisee_n_estJamaisEnRetard() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Zeta", null, d.expert());
        facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client.id(), LocalDate.now().minusDays(60), LocalDate.now().minusDays(30), "Brouillon",
                List.of(new LigneFactureRequest("x", BigDecimal.ONE, new BigDecimal("100"), BigDecimal.ZERO, null))), d.expert());

        assertThat(retards(d)).isZero();
    }
}
