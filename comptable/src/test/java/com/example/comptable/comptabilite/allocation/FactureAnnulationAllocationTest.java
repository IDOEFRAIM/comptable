package com.example.comptable.comptabilite.allocation;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant F0.1 : une facture qui porte des affectations ACTIVES ne s'annule pas (409, rien n'est desaffecte automatiquement) ; sans affectation, ou avec des
 * affectations uniquement ANNULEES, l'annulation suit les regles existantes ; une autre entreprise ne voit pas la facture (404).
 */
class FactureAnnulationAllocationTest extends BankTestBase {

    @Autowired FacturationService facturationService;
    @Autowired PaymentAllocationService allocationService;
    @Autowired PaymentAllocationRepository allocationRepository;

    private FactureDto facture(Dossier d, TiersDto client, int prixHt) {
        UUID produit = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "701").orElseThrow().getId();
        return facturationService.validerFacture(d.id(), facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client.id(), LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(25), "Vente", List.of(new LigneFactureRequest("Marchandise", BigDecimal.ONE, new BigDecimal(prixHt), BigDecimal.ZERO, null))),
                d.expert()).id(), produit, d.expert());
    }

    private UUID ligneClient(UUID ecritureId, String cote) {
        return jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' and l."
                + cote + " > 0", UUID.class, ecritureId);
    }

    private UUID encaissement(Dossier d, TiersDto client, BigDecimal montant) {
        return ligneClient(engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT, client.id(), LocalDate.now(),
                "ENC-" + UUID.randomUUID().toString().substring(0, 5), "Encaissement", montant, MoyenPaiement.VIREMENT), d.expert()).id(), d.expert()).ecritureId(), "credit");
    }

    private int statut(Supplier<?> action) {
        try {
            action.get();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void sansAllocation_l_annulationSuitLesReglesExistantes() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client A", null, d.expert());
        FactureDto f = facture(d, client, 100_000);

        FactureDto annulee = facturationService.annulerFacture(d.id(), f.id(), d.expert());

        assertThat(annulee.statut()).isEqualTo("ANNULEE");
    }

    @Test
    void partiellementAllouee_l_annulationEstRefusee_etRienNEstDesaffecte() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client B", null, d.expert());
        FactureDto f = facture(d, client, 100_000);
        UUID ligneFacture = ligneClient(f.ecritureId(), "debit");
        allocationService.allouer(d.id(), encaissement(d, client, new BigDecimal("40000")), ligneFacture, new BigDecimal("40000"), SourceAllocation.USER, d.expert());

        assertThat(statut(() -> facturationService.annulerFacture(d.id(), f.id(), d.expert()))).isEqualTo(409);

        assertThat(allocationRepository.findActivesDeLaFacture(ligneFacture)).hasSize(1);
        assertThat(facturationService.lister(d.id(), TypeFacture.FACTURE, d.expert()).stream().filter(x -> x.id().equals(f.id())).findFirst().orElseThrow().statut())
                .isEqualTo("VALIDEE");
    }

    @Test
    void totalementAllouee_l_annulationEstRefusee_avecUnMessageActionnable() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client C", null, d.expert());
        FactureDto f = facture(d, client, 100_000);
        UUID ligneFacture = ligneClient(f.ecritureId(), "debit");
        BigDecimal ttc = jdbc.queryForObject("select debit from ligne_ecriture where id = ?", BigDecimal.class, ligneFacture);
        allocationService.allouer(d.id(), encaissement(d, client, ttc), ligneFacture, ttc, SourceAllocation.USER, d.expert());

        try {
            facturationService.annulerFacture(d.id(), f.id(), d.expert());
            throw new AssertionError("l'annulation aurait du etre refusee");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(409);
            assertThat(e.getReason()).contains("affectation").contains("Annulez d'abord");
        }
    }

    @Test
    void affectationsAnnuleesSeulement_l_annulationEstAutorisee() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client D", null, d.expert());
        FactureDto f = facture(d, client, 100_000);
        UUID ligneFacture = ligneClient(f.ecritureId(), "debit");
        var allocation = allocationService.allouer(d.id(), encaissement(d, client, new BigDecimal("30000")), ligneFacture, new BigDecimal("30000"), SourceAllocation.USER, d.expert());
        assertThat(statut(() -> facturationService.annulerFacture(d.id(), f.id(), d.expert()))).isEqualTo(409);

        // Le comptable defait d'abord l'affectation (procedure existante, motif trace) : plus aucune affectation ACTIVE, l'historique REVERSED est conserve.
        allocationService.annuler(d.id(), allocation.getId(), "Erreur d'imputation", d.expert());
        FactureDto annulee = facturationService.annulerFacture(d.id(), f.id(), d.expert());

        assertThat(annulee.statut()).isEqualTo("ANNULEE");
        assertThat(allocationRepository.findByEntreprise(d.id())).hasSize(1).allSatisfy(a -> assertThat(a.getStatus()).isEqualTo(StatutAllocation.REVERSED));
    }

    @Test
    void uneAutreEntreprise_ne_peutPasAnnulerLaFacture() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        TiersDto client = tiersService.creer(a.id(), TypeTiers.CLIENT, "Client E", null, a.expert());
        FactureDto f = facture(a, client, 50_000);

        assertThat(statut(() -> facturationService.annulerFacture(b.id(), f.id(), b.expert()))).isEqualTo(404);
        assertThat(statut(() -> facturationService.annulerFacture(a.id(), f.id(), b.expert()))).isEqualTo(404);
    }
}
