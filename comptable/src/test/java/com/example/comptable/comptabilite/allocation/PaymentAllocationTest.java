package com.example.comptable.comptabilite.allocation;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Allocation des reglements aux factures sur un vrai PostgreSQL : factures d'achat (ligne 401 au credit) et reglements fournisseur
 * (ligne 401 au debit), relies montant par montant. Verifie les invariants, le statut calcule, l'idempotence, l'annulation, le
 * lettrage derive et l'isolation entre entreprises.
 */
class PaymentAllocationTest extends CollecteTestBase {

    @Autowired PaymentAllocationService service;
    @Autowired PaymentAllocationRepository allocationRepository;
    @Autowired LigneEcritureRepository ligneRepository;

    private UUID ligneTiers(UUID ecritureId, String cote) {
        return jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? "
                + "and c.numero like '40%' and l." + cote + " > 0", UUID.class, ecritureId);
    }

    /** Ligne 401 (credit) d'une facture d'achat comptabilisee. */
    private UUID facture(Dossier d, TiersDto fournisseur, int montant) {
        return ligneTiers(acheterSansPiece(d, fournisseur, montant), "credit");
    }

    private UUID facture(Dossier d, int montant) {
        return facture(d, d.fournisseur(), montant);
    }

    /** Ligne 401 (debit) d'un reglement fournisseur valide. */
    private UUID reglement(Dossier d, TiersDto fournisseur, int montant) {
        UUID ecriture = engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(
                AccountingOperationType.REGLEMENT_FOURNISSEUR, fournisseur.id(), LocalDate.now(), "VIR-" + UUID.randomUUID().toString().substring(0, 6),
                "Reglement " + montant, new BigDecimal(montant), MoyenPaiement.VIREMENT), d.expert()).id(), d.expert()).ecritureId();
        return ligneTiers(ecriture, "debit");
    }

    private UUID reglement(Dossier d, int montant) {
        return reglement(d, d.fournisseur(), montant);
    }

    private PaymentAllocation allouer(Dossier d, UUID paiement, UUID facture, int montant) {
        return service.allouer(d.id(), paiement, facture, new BigDecimal(montant), SourceAllocation.USER, d.expert());
    }

    private PaymentAllocationService.Situation situation(Dossier d, UUID facture) {
        return service.situationFacture(d.id(), facture, d.expert());
    }

    @Test
    void paiementExact_uneFactureUnReglement_facturePayeeEtLettree() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 1000);
        UUID paiement = reglement(d, 1000);

        allouer(d, paiement, facture, 1000);

        assertThat(situation(d, facture).statut()).isEqualTo(StatutPaiement.PAYEE);
        assertThat(situation(d, facture).reste()).isEqualByComparingTo("0");
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNotNull();
        assertThat(ligneRepository.findById(paiement).orElseThrow().getLettrage())
                .isEqualTo(ligneRepository.findById(facture).orElseThrow().getLettrage());
        assertThat(evenements("PAYMENT_ALLOCATED")).isNotEmpty();
        assertThat(evenements("LETTRAGE_CREATED")).isNotEmpty();
    }

    @Test
    void paiementPartiel_factureResteOuverteEtNonLettree() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 1000);
        UUID paiement = reglement(d, 600);

        allouer(d, paiement, facture, 600);

        var s = situation(d, facture);
        assertThat(s.statut()).isEqualTo(StatutPaiement.PARTIELLEMENT_PAYEE);
        assertThat(s.alloue()).isEqualByComparingTo("600");
        assertThat(s.reste()).isEqualByComparingTo("400");
        // Un reglement partiel ne rend jamais la facture « lettree ».
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNull();
        assertThat(ligneRepository.findById(paiement).orElseThrow().getLettrage()).isNull();
    }

    @Test
    void deuxReglementsUneFacture_soldeEtLettrageSeulementQuandToutEstAlloue() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 1000);
        UUID premier = reglement(d, 600);
        UUID second = reglement(d, 400);

        allouer(d, premier, facture, 600);
        assertThat(situation(d, facture).statut()).isEqualTo(StatutPaiement.PARTIELLEMENT_PAYEE);
        allouer(d, second, facture, 400);

        assertThat(situation(d, facture).statut()).isEqualTo(StatutPaiement.PAYEE);
        String code = ligneRepository.findById(facture).orElseThrow().getLettrage();
        assertThat(code).isNotNull();
        assertThat(ligneRepository.findById(premier).orElseThrow().getLettrage()).isEqualTo(code);
        assertThat(ligneRepository.findById(second).orElseThrow().getLettrage()).isEqualTo(code);
    }

    @Test
    void unReglementTroisFactures_repartitionManuelleSansFausseAssociation() {
        Dossier d = nouveauDossier(false);
        UUID f1 = facture(d, 300);
        UUID f2 = facture(d, 500);
        UUID f3 = facture(d, 200);
        UUID paiement = reglement(d, 1000);

        allouer(d, paiement, f1, 300);
        assertThat(service.disponiblePaiement(d.id(), paiement, d.expert()).nonAffecte()).isEqualByComparingTo("700");
        allouer(d, paiement, f2, 500);
        allouer(d, paiement, f3, 200);

        assertThat(List.of(f1, f2, f3)).allSatisfy(f -> assertThat(situation(d, f).statut()).isEqualTo(StatutPaiement.PAYEE));
        assertThat(service.disponiblePaiement(d.id(), paiement, d.expert()).nonAffecte()).isEqualByComparingTo("0");
        assertThat(allocationRepository.findActivesDuPaiement(paiement)).hasSize(3);
    }

    @Test
    void surpaiement_leReliquatResteNonAffecte_etLaFactureNEstPasSurpayee() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 1000);
        UUID paiement = reglement(d, 1300);

        allouer(d, paiement, facture, 1000);

        assertThat(situation(d, facture).statut()).isEqualTo(StatutPaiement.PAYEE);
        assertThat(situation(d, facture).alloue()).isEqualByComparingTo("1000");
        assertThat(service.disponiblePaiement(d.id(), paiement, d.expert()).nonAffecte()).isEqualByComparingTo("300");
        // Le reglement n'est pas soldé : aucun lettrage artificiel.
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNull();
    }

    @Test
    void allocationDepassantLePaiement_estRefusee() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 1000);
        UUID paiement = reglement(d, 400);

        assertThatThrownBy(() -> allouer(d, paiement, facture, 500)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("disponible");
        assertThat(allocationRepository.findActivesDuPaiement(paiement)).isEmpty();
    }

    @Test
    void allocationDepassantLeSoldeDeLaFacture_estRefusee() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 500);
        UUID paiement = reglement(d, 1000);
        allouer(d, paiement, facture, 300);

        UUID autrePaiement = reglement(d, 1000);
        assertThatThrownBy(() -> allouer(d, autrePaiement, facture, 300)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ouvert");
        assertThat(situation(d, facture).alloue()).isEqualByComparingTo("300");
    }

    @Test
    void montantNulOuNegatif_estRefuse() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 500);
        UUID paiement = reglement(d, 500);

        assertThatThrownBy(() -> allouer(d, paiement, facture, 0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> allouer(d, paiement, facture, -5)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void tiersDifferent_estRefuse() {
        Dossier d = nouveauDossier(false);
        TiersDto autre = tiersService.creer(d.id(), TypeTiers.FOURNISSEUR, "Autre fournisseur", null, d.expert());
        UUID facture = facture(d, 700);
        UUID paiementAutre = reglement(d, autre, 700);

        assertThatThrownBy(() -> allouer(d, paiementAutre, facture, 700)).isInstanceOf(ResponseStatusException.class);
        assertThat(allocationRepository.findActivesDuPaiement(paiementAutre)).isEmpty();
    }

    @Test
    void sensIncompatible_deuxFacturesOuDeuxReglements_refuse() {
        Dossier d = nouveauDossier(false);
        UUID f1 = facture(d, 500);
        UUID f2 = facture(d, 500);
        UUID p1 = reglement(d, 500);
        UUID p2 = reglement(d, 500);

        assertThatThrownBy(() -> allouer(d, f1, f2, 100)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> allouer(d, p1, p2, 100)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void entrepriseDifferente_estRefusee_etNInventeAucuneLigne() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        UUID factureA = facture(a, 800);
        UUID paiementB = reglement(b, 800);

        // Depuis B : la facture de A est introuvable. Depuis A : le reglement de B aussi.
        assertThatThrownBy(() -> service.allouer(b.id(), paiementB, factureA, new BigDecimal("800"), SourceAllocation.USER, b.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
        assertThatThrownBy(() -> service.allouer(a.id(), paiementB, factureA, new BigDecimal("800"), SourceAllocation.USER, a.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
        assertThatThrownBy(() -> service.situationFacture(b.id(), factureA, b.expert())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void doubleConfirmation_estIdempotente_etUnAutreMontantEstUnConflit() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 1000);
        UUID paiement = reglement(d, 1000);

        PaymentAllocation premiere = allouer(d, paiement, facture, 600);
        PaymentAllocation seconde = allouer(d, paiement, facture, 600);

        assertThat(seconde.getId()).isEqualTo(premiere.getId());
        assertThat(allocationRepository.findActivesDuPaiement(paiement)).hasSize(1);
        assertThatThrownBy(() -> allouer(d, paiement, facture, 700)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("annulez");
    }

    @Test
    void doubleConfirmationConcurrente_neCreeQuUneAllocation() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 1000);
        UUID paiement = reglement(d, 1000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch depart = new CountDownLatch(1);
        Callable<UUID> tache = () -> {
            depart.await();
            return allouer(d, paiement, facture, 1000).getId();
        };
        Future<UUID> a = pool.submit(tache);
        Future<UUID> b = pool.submit(tache);
        depart.countDown();

        assertThat(a.get()).isEqualTo(b.get());
        pool.shutdown();
        assertThat(allocationRepository.findActivesDuPaiement(paiement)).hasSize(1);
    }

    @Test
    void annulation_restaureLeStatutLeLettrageEtGardeLHistorique() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 1000);
        UUID paiement = reglement(d, 1000);
        PaymentAllocation allocation = allouer(d, paiement, facture, 1000);
        assertThat(situation(d, facture).statut()).isEqualTo(StatutPaiement.PAYEE);
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNotNull();

        assertThatThrownBy(() -> service.annuler(d.id(), allocation.getId(), " ", d.expert())).isInstanceOf(ResponseStatusException.class);
        PaymentAllocation annulee = service.annuler(d.id(), allocation.getId(), "Erreur d'affectation", d.expert());

        assertThat(annulee.getStatus()).isEqualTo(StatutAllocation.REVERSED);
        assertThat(annulee.getReversedBy()).isEqualTo(d.expert().getId());
        assertThat(annulee.getReversalReason()).isEqualTo("Erreur d'affectation");
        assertThat(situation(d, facture).statut()).isEqualTo(StatutPaiement.NON_PAYEE);
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNull();
        assertThat(ligneRepository.findById(paiement).orElseThrow().getLettrage()).isNull();
        assertThat(allocationRepository.findById(allocation.getId())).isPresent(); // jamais supprimee
        assertThat(service.annuler(d.id(), allocation.getId(), "encore", d.expert()).getStatus()).isEqualTo(StatutAllocation.REVERSED); // idempotent
        // Le paiement est de nouveau disponible en entier : on peut reallouer.
        allouer(d, paiement, facture, 1000);
        assertThat(situation(d, facture).statut()).isEqualTo(StatutPaiement.PAYEE);
        assertThat(evenements("PAYMENT_ALLOCATION_REVERSED")).isNotEmpty();
        assertThat(evenements("LETTRAGE_REVERSED")).isNotEmpty();
    }

    @Test
    void recalculDuStatut_suitLesAllocationsActivesSeulement() {
        Dossier d = nouveauDossier(false);
        UUID facture = facture(d, 1000);
        UUID p1 = reglement(d, 400);
        UUID p2 = reglement(d, 300);
        PaymentAllocation a1 = allouer(d, p1, facture, 400);
        allouer(d, p2, facture, 300);
        assertThat(situation(d, facture).reste()).isEqualByComparingTo("300");

        service.annuler(d.id(), a1.getId(), "Mauvais reglement", d.expert());

        var s = situation(d, facture);
        assertThat(s.alloue()).isEqualByComparingTo("300");
        assertThat(s.reste()).isEqualByComparingTo("700");
        assertThat(s.statut()).isEqualTo(StatutPaiement.PARTIELLEMENT_PAYEE);
        assertThat(service.situations(d.id(), List.of(facture)).get(facture).statut()).isEqualTo(StatutPaiement.PARTIELLEMENT_PAYEE);
    }

    @Test
    void statutCalcule_fonctionPure() {
        assertThat(PaymentAllocationService.statutDe(new BigDecimal("1000"), BigDecimal.ZERO)).isEqualTo(StatutPaiement.NON_PAYEE);
        assertThat(PaymentAllocationService.statutDe(new BigDecimal("1000"), new BigDecimal("1"))).isEqualTo(StatutPaiement.PARTIELLEMENT_PAYEE);
        assertThat(PaymentAllocationService.statutDe(new BigDecimal("1000"), new BigDecimal("1000"))).isEqualTo(StatutPaiement.PAYEE);
    }
}
