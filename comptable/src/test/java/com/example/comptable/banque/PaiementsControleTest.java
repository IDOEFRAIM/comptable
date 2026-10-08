package com.example.comptable.banque;

import com.example.comptable.comptabilite.allocation.PaymentAllocationRepository;
import com.example.comptable.comptabilite.allocation.PaymentAllocationService;
import com.example.comptable.comptabilite.allocation.SourceAllocation;
import com.example.comptable.comptabilite.allocation.StatutPaiement;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.controle.ControlCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reglements et factures de bout en bout : allocation automatique des seuls cas certains, constats quand une decision humaine est necessaire,
 * transaction bancaire couvrant plusieurs factures (jamais une facture choisie arbitrairement) et plusieurs transactions pour une facture.
 */
class PaiementsControleTest extends BankTestBase {

    private static final List<String> CODES_PAIEMENT = List.of(ControlCodes.PAYMENT_WITHOUT_INVOICE, ControlCodes.OVERPAYMENT,
            ControlCodes.PAYMENT_ALLOCATION_NEEDS_REVIEW);

    @Autowired PaymentAllocationService allocationService;
    @Autowired PaymentAllocationRepository allocationRepository;
    @Autowired LigneEcritureRepository ligneRepository;
    @Autowired RapprochementBancaireService rapprochementService;
    @Autowired BankLineageService lineageService;

    private void controler(Dossier d) {
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
    }

    private long constats(Dossier d, String... codes) {
        return findingRepository.findActifsParCodes(d.id(), List.of(codes)).size();
    }

    private void allouer(Dossier d, UUID paiement, UUID facture, int montant) {
        allocationService.allouer(d.id(), paiement, facture, new BigDecimal(montant), SourceAllocation.USER, d.expert());
    }

    private StatutPaiement statut(Dossier d, UUID facture) {
        return allocationService.situationFacture(d.id(), facture, d.expert()).statut();
    }

    @Test
    void casCertain_unePaireDeMemeMontant_estAlloueeEtLettreeSeule_sansAucunConstat() {
        Dossier d = nouveauDossier(false);
        UUID facture = factureAchat(d, 1000);
        Reglement r = reglement(d, 1000, LocalDate.now(), "Reglement A");

        controler(d);

        var allocations = allocationRepository.findActivesDuPaiement(r.ligneTiersId());
        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).getSource()).isEqualTo(SourceAllocation.SYSTEM_EXACT);
        assertThat(allocations.get(0).getCreatedBy()).isNull();
        assertThat(statut(d, facture)).isEqualTo(StatutPaiement.PAYEE);
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNotNull();
        assertThat(constats(d, CODES_PAIEMENT.toArray(String[]::new))).isZero();
        // Lineage dans les deux sens : reglement -> facture, facture -> reglement.
        UUID ecritureFacture = jdbc.queryForObject("select ecriture_id from ligne_ecriture where id = ?", UUID.class, facture);
        var depuisReglement = lineageService.depuisEcriture(d.id(), r.ecritureId(), d.expert());
        assertThat(depuisReglement.affectations()).hasSize(1);
        assertThat(depuisReglement.affectations().get(0).role()).isEqualTo("REGLEMENT");
        assertThat(depuisReglement.affectations().get(0).contrepartieEcritureId()).isEqualTo(ecritureFacture);
        var depuisFacture = lineageService.depuisEcriture(d.id(), ecritureFacture, d.expert());
        assertThat(depuisFacture.affectations()).hasSize(1);
        assertThat(depuisFacture.affectations().get(0).role()).isEqualTo("FACTURE");
        assertThat(depuisFacture.affectations().get(0).contrepartieEcritureId()).isEqualTo(r.ecritureId());
        // Rejouer ne change rien.
        controler(d);
        assertThat(allocationRepository.findActivesDuPaiement(r.ligneTiersId())).hasSize(1);
    }

    @Test
    void trentePaires_exactes_n_encombrentPasLInbox() {
        Dossier d = nouveauDossier(false);
        for (int i = 0; i < 12; i++) {
            factureAchat(d, 1000 + i * 7);
            reglement(d, 1000 + i * 7, LocalDate.now(), "Reglement " + i);
        }

        controler(d);

        assertThat(jdbc.queryForObject("select count(*) from payment_allocation where entreprise_id = ? and status = 'ACTIVE'", Long.class, d.id())).isEqualTo(12);
        assertThat(constats(d, CODES_PAIEMENT.toArray(String[]::new))).isZero();
    }

    @Test
    void deuxFacturesDeMemeMontant_aucuneN_estChoisie_etLeReglementEstSignale() {
        Dossier d = nouveauDossier(false);
        UUID f1 = factureAchat(d, 500);
        UUID f2 = factureAchat(d, 500);
        Reglement r = reglement(d, 500, LocalDate.now(), "Reglement A");

        controler(d);

        assertThat(allocationRepository.findActivesDuPaiement(r.ligneTiersId())).isEmpty();
        assertThat(statut(d, f1)).isEqualTo(StatutPaiement.NON_PAYEE);
        assertThat(statut(d, f2)).isEqualTo(StatutPaiement.NON_PAYEE);
        assertThat(constats(d, ControlCodes.PAYMENT_ALLOCATION_NEEDS_REVIEW)).isEqualTo(1);

        // Le comptable tranche : le constat disparait de lui-meme au controle suivant.
        allouer(d, r.ligneTiersId(), f2, 500);
        controler(d);
        assertThat(statut(d, f2)).isEqualTo(StatutPaiement.PAYEE);
        assertThat(statut(d, f1)).isEqualTo(StatutPaiement.NON_PAYEE);
        assertThat(constats(d, ControlCodes.PAYMENT_ALLOCATION_NEEDS_REVIEW)).isZero();
    }

    @Test
    void reglementSansFacture_estSignale() {
        Dossier d = nouveauDossier(false);
        reglement(d, 700, LocalDate.now(), "Reglement orphelin");

        controler(d);

        assertThat(constats(d, ControlCodes.PAYMENT_WITHOUT_INVOICE)).isEqualTo(1);
    }

    @Test
    void surpaiement_leReliquatResteNonAffecte_etEstSignale() {
        Dossier d = nouveauDossier(false);
        UUID facture = factureAchat(d, 500);
        Reglement r = reglement(d, 800, LocalDate.now(), "Reglement trop eleve");
        allouer(d, r.ligneTiersId(), facture, 500);

        controler(d);

        assertThat(statut(d, facture)).isEqualTo(StatutPaiement.PAYEE);
        assertThat(allocationService.disponiblePaiement(d.id(), r.ligneTiersId(), d.expert()).nonAffecte()).isEqualByComparingTo("300");
        assertThat(constats(d, ControlCodes.OVERPAYMENT)).isEqualTo(1);
    }

    @Test
    void uneTransactionBancaireCouvrantTroisFactures_n_associeAucuneFactureArbitrairement() {
        Dossier d = nouveauDossier(false);
        UUID f1 = factureAchat(d, 300);
        UUID f2 = factureAchat(d, 500);
        UUID f3 = factureAchat(d, 200);
        Reglement r = reglement(d, 1000, LocalDate.now(), "Reglement groupe");
        var releve = rapprochementService.importer(d.id(), new ImporterReleveRequest(r.compteBanqueId(), "Releve", null, null, null, null),
                csv(LocalDate.now() + ";VIR GROUPE STATION XYZ;-1000"), d.expert());
        assertThat(releve.lignes().get(0).pointee()).isTrue(); // banque -> reglement : certain

        controler(d);

        // Reglement -> factures : aucune allocation automatique, aucune facture « premiere ».
        assertThat(allocationRepository.findActivesDuPaiement(r.ligneTiersId())).isEmpty();
        assertThat(List.of(f1, f2, f3)).allSatisfy(f -> assertThat(statut(d, f)).isEqualTo(StatutPaiement.NON_PAYEE));
        assertThat(constats(d, ControlCodes.PAYMENT_ALLOCATION_NEEDS_REVIEW)).isEqualTo(1);

        // Repartition manuelle.
        allouer(d, r.ligneTiersId(), f1, 300);
        allouer(d, r.ligneTiersId(), f2, 500);
        allouer(d, r.ligneTiersId(), f3, 200);
        controler(d);

        assertThat(List.of(f1, f2, f3)).allSatisfy(f -> assertThat(statut(d, f)).isEqualTo(StatutPaiement.PAYEE));
        assertThat(ligneRepository.findById(r.ligneTiersId()).orElseThrow().getLettrage()).isNotNull();
        assertThat(constats(d, CODES_PAIEMENT.toArray(String[]::new))).isZero();
    }

    @Test
    void troisTransactionsPourUneFacture_lesPaiementsPartielsSecumulent() {
        Dossier d = nouveauDossier(false);
        UUID facture = factureAchat(d, 1000);
        Reglement a = reglement(d, 600, LocalDate.now(), "Acompte 1");
        Reglement b = reglement(d, 250, LocalDate.now(), "Acompte 2");
        Reglement c = reglement(d, 150, LocalDate.now(), "Solde");
        rapprochementService.importer(d.id(), new ImporterReleveRequest(a.compteBanqueId(), "Releve", null, null, null, null),
                csv(LocalDate.now() + ";VIR STATION XYZ ACOMPTE;-600", LocalDate.now() + ";VIR STATION XYZ ACOMPTE 2;-250",
                        LocalDate.now() + ";VIR STATION XYZ SOLDE;-150"), d.expert());

        controler(d);
        assertThat(statut(d, facture)).isEqualTo(StatutPaiement.NON_PAYEE); // montants differents : rien d'automatique

        allouer(d, a.ligneTiersId(), facture, 600);
        allouer(d, b.ligneTiersId(), facture, 250);
        var apresDeux = allocationService.situationFacture(d.id(), facture, d.expert());
        assertThat(apresDeux.statut()).isEqualTo(StatutPaiement.PARTIELLEMENT_PAYEE);
        assertThat(apresDeux.reste()).isEqualByComparingTo("150");
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNull();

        allouer(d, c.ligneTiersId(), facture, 150);
        assertThat(statut(d, facture)).isEqualTo(StatutPaiement.PAYEE);
        assertThat(ligneRepository.findById(facture).orElseThrow().getLettrage()).isNotNull();
    }

    @Test
    void isolationMultiTenant_l_allocationAutomatiqueNeMelangeJamaisLesEntreprises() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        UUID factureA = factureAchat(a, 4321);
        Reglement reglementB = reglement(b, 4321, LocalDate.now(), "Reglement B");

        controler(a);
        controler(b);

        assertThat(statut(a, factureA)).isEqualTo(StatutPaiement.NON_PAYEE);
        assertThat(allocationRepository.findActivesDuPaiement(reglementB.ligneTiersId())).isEmpty();
        assertThat(constats(b, ControlCodes.PAYMENT_WITHOUT_INVOICE)).isEqualTo(1);
    }
}
