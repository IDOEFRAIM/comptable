package com.example.comptable.banque;

import com.example.comptable.banque.BanqueDto.StatutTransaction;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.controle.ControlCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Virement interne (banque A -> banque B) : ni charge, ni produit, ni reglement fournisseur, ni encaissement client. Les deux jambes sont liees ; une
 * ecriture de transfert deja existante est rapprochee, jamais creee ; un cas probable est propose, un cas ambigu n'est jamais tranche.
 */
class VirementsInternesTest extends BankTestBase {

    @Autowired RapprochementBancaireService rapprochementService;
    @Autowired InternalTransferService transferService;
    @Autowired BankInternalTransferRepository transferRepository;
    @Autowired BankReconciliationRepository reconciliationRepository;
    @Autowired BankTransactionQueryService queryService;
    @Autowired LigneReleveRepository ligneReleveRepository;

    private UUID banqueB(Dossier d) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into compte_comptable (id, entreprise_id, numero, intitule, niveau) values (?, ?, '5212', 'Banque B', 3)", id, d.id());
        return id;
    }

    private void importer(Dossier d, UUID compte, String... lignes) {
        MockMultipartFile fichier = new MockMultipartFile("fichier", "releve.csv", "text/csv",
                ("date;libelle;montant\n" + String.join("\n", lignes) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rapprochementService.importerComplet(d.id(), new BankStatementImporter.DemandeImport(compte, "Releve", null, null, null, null, null), fichier, d.expert());
    }

    private void controler(Dossier d) {
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
    }

    private long constats(Dossier d, String... codes) {
        return findingRepository.findActifsParCodes(d.id(), List.of(codes)).size();
    }

    private List<BankInternalTransfer> transferts(Dossier d) {
        return transferRepository.findAll().stream().filter(t -> t.getEntrepriseId().equals(d.id())).toList();
    }

    private StatutTransaction statutDe(Dossier d, String libelleContient) {
        return queryService.lister(d.id(), null, null, null, libelleContient, 0, 50, d.expert()).lignes().stream()
                .map(l -> StatutTransaction.valueOf(l.statut())).findFirst().orElseThrow();
    }

    @Test
    void exact_libellesDeVirementInterne_lesDeuxJambesSontLieesEtRetireesDuRapprochementAvecLesTiers() {
        Dossier d = nouveauDossier(false);
        String jour = LocalDate.now().toString();
        Reglement fournisseur = reglement(d, 50000, LocalDate.now(), "Reglement fournisseur de meme montant");
        UUID compteB = banqueB(d);

        importer(d, fournisseur.compteBanqueId(), jour + ";VIREMENT INTERNE VERS BANQUE B;-50000");
        importer(d, compteB, jour + ";VIREMENT INTERNE DE BANQUE A;50000");

        BankInternalTransfer t = transferts(d).get(0);
        assertThat(t.getStatus()).isEqualTo(StatutReconciliation.CONFIRMED);
        assertThat(t.getSource()).isEqualTo(SourceReconciliation.SYSTEM_EXACT);
        assertThat(t.getAmount()).isEqualByComparingTo("50000");
        assertThat(t.getEcritureId()).isNull(); // rien n'a ete invente
        // La sortie n'est PAS rapprochee au reglement fournisseur de meme montant : c'est un virement interne.
        assertThat(ligneReleveRepository.findById(t.getDebitTransactionId()).orElseThrow().getLigneEcritureId()).isNull();
        assertThat(reconciliationRepository.findConfirmeeSurCible(fournisseur.ligneBanqueId())).isEmpty();
        assertThat(statutDe(d, "VERS BANQUE B")).isEqualTo(StatutTransaction.VIREMENT_INTERNE);
        assertThat(statutDe(d, "DE BANQUE A")).isEqualTo(StatutTransaction.VIREMENT_INTERNE);
        assertThat(evenements("BANK_INTERNAL_TRANSFER_MATCHED_AUTOMATICALLY")).isNotEmpty();

        // Ni « sans correspondance » ni « a confirmer » : seul constat, l'ecriture de transfert a enregistrer.
        controler(d);
        assertThat(constats(d, ControlCodes.UNMATCHED_PAYMENT)).isZero();
        assertThat(constats(d, ControlCodes.INTERNAL_TRANSFER_WITHOUT_ENTRY)).isEqualTo(1);
        var synthese = queryService.synthese(d.id(), null, null, d.expert());
        assertThat(synthese.virementsInternes()).isEqualTo(2);
        assertThat(synthese.montantNonRapproche()).isEqualByComparingTo("0");
    }

    @Test
    void ecritureDeTransfertExistante_lesDeuxJambesYSontRapprochees_sansConstat() {
        Dossier d = nouveauDossier(false);
        String jour = LocalDate.now().toString();
        Reglement modele = reglement(d, 1, LocalDate.now(), "Modele");
        UUID compteB = banqueB(d);
        // Ecriture de transfert DEJA comptabilisee : debit banque B / credit banque A (aucune n'est creee par le module).
        Map<String, Object> m = jdbc.queryForMap("select exercice_id, journal_id from ecriture_comptable where id = ?", modele.ecritureId());
        UUID ecriture = UUID.randomUUID();
        jdbc.update("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut) values (?, ?, ?, ?, 'TRF-1', current_date, 'Mouvement de fonds', 'VALIDEE')",
                ecriture, d.id(), m.get("exercice_id"), m.get("journal_id"));
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) values (gen_random_uuid(), ?, ?, 'Banque B', 25000, 0, 1)", ecriture, compteB);
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) values (gen_random_uuid(), ?, ?, 'Banque A', 0, 25000, 2)", ecriture, modele.compteBanqueId());

        importer(d, modele.compteBanqueId(), jour + ";VIREMENT INTERNE VERS BANQUE B;-25000");
        importer(d, compteB, jour + ";VIREMENT INTERNE DE BANQUE A;25000");

        BankInternalTransfer t = transferts(d).get(0);
        assertThat(t.getEcritureId()).isEqualTo(ecriture);
        assertThat(reconciliationRepository.findConfirmeeDe(t.getDebitTransactionId())).isPresent();
        assertThat(reconciliationRepository.findConfirmeeDe(t.getCreditTransactionId())).isPresent();
        controler(d);
        assertThat(constats(d, ControlCodes.INTERNAL_TRANSFER_WITHOUT_ENTRY, ControlCodes.UNMATCHED_PAYMENT, ControlCodes.INTERNAL_TRANSFER_TO_CONFIRM)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ?", Long.class, d.id())).isEqualTo(2); // modele + transfert deja la

        // Annuler le virement defait aussi les deux rapprochements qu'il avait permis.
        transferService.annuler(d.id(), t.getId(), "Ce n'etait pas un virement interne", d.expert());
        assertThat(reconciliationRepository.findConfirmeeDe(t.getDebitTransactionId())).isEmpty();
        assertThat(ligneReleveRepository.findById(t.getCreditTransactionId()).orElseThrow().getLigneEcritureId()).isNull();
        assertThat(transferRepository.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(StatutReconciliation.REVERSED);
        assertThat(evenements("BANK_INTERNAL_TRANSFER_REVERSED")).isNotEmpty();
    }

    @Test
    void probable_sansPreuve_estPropose_confirmeOuRejeteParLeComptable() {
        Dossier d = nouveauDossier(false);
        String jour = LocalDate.now().toString();
        Reglement modele = reglement(d, 1, LocalDate.now(), "Modele");
        UUID compteB = banqueB(d);

        importer(d, modele.compteBanqueId(), jour + ";VIR 80000;-80000");
        importer(d, compteB, jour + ";VIR RECU 80000;80000");

        BankInternalTransfer t = transferts(d).get(0);
        assertThat(t.getStatus()).isEqualTo(StatutReconciliation.PROPOSED);
        assertThat(statutDe(d, "VIR RECU")).isEqualTo(StatutTransaction.A_CONFIRMER);
        controler(d);
        assertThat(constats(d, ControlCodes.INTERNAL_TRANSFER_TO_CONFIRM)).isEqualTo(1);
        assertThat(constats(d, ControlCodes.UNMATCHED_PAYMENT)).isZero(); // les jambes ne sont pas « sans correspondance »

        BankInternalTransfer confirme = transferService.confirmer(d.id(), t.getId(), d.expert());
        assertThat(confirme.getStatus()).isEqualTo(StatutReconciliation.CONFIRMED);
        assertThat(confirme.getConfirmedBy()).isEqualTo(d.expert().getId());
        assertThat(transferService.confirmer(d.id(), t.getId(), d.expert()).getId()).isEqualTo(t.getId()); // double confirmation
        assertThat(statutDe(d, "VIR RECU")).isEqualTo(StatutTransaction.VIREMENT_INTERNE);

        // Rejet : memorise, les deux transactions retournent au rapprochement normal et ne sont jamais reproposees.
        Dossier e = nouveauDossier(false);
        Reglement modeleE = reglement(e, 1, LocalDate.now(), "Modele");
        UUID compteBE = banqueB(e);
        importer(e, modeleE.compteBanqueId(), jour + ";VIR 61000;-61000");
        importer(e, compteBE, jour + ";VIR RECU 61000;61000");
        BankInternalTransfer propose = transferts(e).get(0);
        transferService.rejeter(e.id(), propose.getId(), "Deux operations sans rapport", e.expert());
        controler(e);
        assertThat(transferRepository.findById(propose.getId()).orElseThrow().getStatus()).isEqualTo(StatutReconciliation.REJECTED);
        assertThat(transferts(e)).hasSize(1);
        assertThat(statutDe(e, "VIR RECU")).isEqualTo(StatutTransaction.SANS_CORRESPONDANCE);
    }

    @Test
    void ambigu_deuxEntreesPossibles_aucuneN_EstChoisie_etUnConstatEstOuvert() {
        Dossier d = nouveauDossier(false);
        String jour = LocalDate.now().toString();
        Reglement modele = reglement(d, 1, LocalDate.now(), "Modele");
        UUID compteB = banqueB(d);

        importer(d, modele.compteBanqueId(), jour + ";VIREMENT INTERNE;-30000");
        importer(d, compteB, jour + ";VIREMENT INTERNE A;30000", jour + ";VIREMENT INTERNE B;30000");

        assertThat(transferts(d)).isEmpty();
        controler(d);
        assertThat(constats(d, ControlCodes.INTERNAL_TRANSFER_AMBIGUOUS)).isEqualTo(1);
        assertThat(ligneReleveRepository.findAll().stream().filter(l -> l.getEntrepriseId().equals(d.id())).allMatch(l -> l.getLigneEcritureId() == null)).isTrue();
    }

    @Test
    void memeCompte_ou_montantsDifferents_neFontJamaisUnVirementInterne() {
        Dossier d = nouveauDossier(false);
        String jour = LocalDate.now().toString();
        Reglement modele = reglement(d, 1, LocalDate.now(), "Modele");
        UUID compteB = banqueB(d);

        importer(d, modele.compteBanqueId(), jour + ";VIREMENT INTERNE;-1000", jour + ";VIREMENT INTERNE;1000");
        importer(d, compteB, jour + ";VIREMENT INTERNE;2000");

        assertThat(transferts(d)).isEmpty();
    }

    @Test
    void isolationMultiTenant_unVirementInterneEstInvisibleEtInactionnablePourUneAutreEntreprise() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        String jour = LocalDate.now().toString();
        Reglement modele = reglement(a, 1, LocalDate.now(), "Modele");
        UUID compteB = banqueB(a);
        importer(a, modele.compteBanqueId(), jour + ";VIR 70000;-70000");
        importer(a, compteB, jour + ";VIR RECU 70000;70000");
        BankInternalTransfer t = transferts(a).get(0);

        assertThatThrownBy(() -> transferService.confirmer(b.id(), t.getId(), b.expert())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> transferService.rejeter(b.id(), t.getId(), "x", b.expert())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> transferService.annuler(b.id(), t.getId(), "x", b.expert())).isInstanceOf(ResponseStatusException.class);
        assertThat(transferts(b)).isEmpty();
        controler(b);
        assertThat(constats(b, ControlCodes.INTERNAL_TRANSFER_TO_CONFIRM, ControlCodes.INTERNAL_TRANSFER_AMBIGUOUS, ControlCodes.INTERNAL_TRANSFER_WITHOUT_ENTRY)).isZero();
    }
}
