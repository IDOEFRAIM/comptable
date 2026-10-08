package com.example.comptable.banque;

import com.example.comptable.comptabilite.allocation.PaymentAllocationService;
import com.example.comptable.comptabilite.allocation.SourceAllocation;
import com.example.comptable.comptabilite.allocation.StatutPaiement;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.operation.vente.NatureVente;
import com.example.comptable.comptabilite.operation.vente.VenteOperationRequest;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.ControlCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cote CLIENT : facture de vente (ligne 411 au debit) et reglement client (ligne 411 au credit) : memes regles que cote fournisseur. */
class PaiementsClientsTest extends BankTestBase {

    @Autowired PaymentAllocationService allocationService;
    @Autowired LigneEcritureRepository ligneRepository;

    private UUID venteLigneClient(Dossier d, TiersDto client, int montant) {
        UUID produit = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "701").orElseThrow().getId();
        UUID ecriture = engine.valider(d.id(), engine.proposerVente(d.id(), new VenteOperationRequest(NatureVente.VENTE_MARCHANDISE, client.id(),
                LocalDate.now(), "V-" + UUID.randomUUID().toString().substring(0, 5), "Vente " + montant, new BigDecimal(montant), produit, null, null,
                null, null), d.expert()).id(), d.expert()).ecritureId();
        return jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' and l.debit > 0",
                UUID.class, ecriture);
    }

    private int ttc(UUID ligne) {
        return jdbc.queryForObject("select debit from ligne_ecriture where id = ?", java.math.BigDecimal.class, ligne).intValue();
    }

    private UUID reglementClient(Dossier d, TiersDto client, int montant) {
        UUID ecriture = engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT,
                client.id(), LocalDate.now(), "ENC-" + UUID.randomUUID().toString().substring(0, 5), "Encaissement " + montant, new BigDecimal(montant),
                MoyenPaiement.VIREMENT), d.expert()).id(), d.expert()).ecritureId();
        return jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' and l.credit > 0",
                UUID.class, ecriture);
    }

    @Test
    void factureClient_paiementPartielPuisSolde_statutCalculeEtLettrage() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Alpha", null, d.expert());
        UUID facture = venteLigneClient(d, client, 1000);
        int total = ttc(facture); // la vente porte la TVA : le du est le TTC
        UUID acompte = reglementClient(d, client, 600);
        UUID solde = reglementClient(d, client, total - 600);

        allocationService.allouer(d.id(), acompte, facture, new BigDecimal("600"), SourceAllocation.USER, d.expert());
        var partiel = allocationService.situationFacture(d.id(), facture, d.expert());
        assertThat(partiel.statut()).isEqualTo(StatutPaiement.PARTIELLEMENT_PAYEE);
        assertThat(partiel.reste()).isEqualByComparingTo(String.valueOf(total - 600));
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNull();

        allocationService.allouer(d.id(), solde, facture, new BigDecimal(total - 600), SourceAllocation.USER, d.expert());
        assertThat(allocationService.situationFacture(d.id(), facture, d.expert()).statut()).isEqualTo(StatutPaiement.PAYEE);
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNotNull();
    }

    @Test
    void clientEtFournisseur_neSeMelangentJamais() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Beta", null, d.expert());
        UUID venteClient = venteLigneClient(d, client, 800);
        UUID reglementFournisseur = reglement(d, 800, LocalDate.now(), "Reglement fournisseur").ligneTiersId();

        assertThatThrownBy(() -> allocationService.allouer(d.id(), reglementFournisseur, venteClient, new BigDecimal("500"), SourceAllocation.USER, d.expert()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void casCertainClient_estAlloueSeul_sansConstat() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Gamma", null, d.expert());
        UUID facture = venteLigneClient(d, client, 2500);
        reglementClient(d, client, ttc(facture));

        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());

        assertThat(allocationService.situationFacture(d.id(), facture, d.expert()).statut()).isEqualTo(StatutPaiement.PAYEE);
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.PAYMENT_WITHOUT_INVOICE, ControlCodes.OVERPAYMENT,
                ControlCodes.PAYMENT_ALLOCATION_NEEDS_REVIEW))).isEmpty();
    }
}
