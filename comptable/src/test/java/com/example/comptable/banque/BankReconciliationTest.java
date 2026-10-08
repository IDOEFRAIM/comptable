package com.example.comptable.banque;

import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.service.RapprochementAutomatiqueService;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.comptabilite.web.dto.ReleveBancaireDto;
import com.example.comptable.controle.ControlCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Import et rapprochement bancaires sur un vrai PostgreSQL : reglements fournisseur comptabilises par le moteur (ligne de banque au credit),
 * releves CSV importes, rapprochement EXACT automatique, PROBABLE propose, AMBIGUOUS et sans correspondance remontes en constats.
 */
class BankReconciliationTest extends BankTestBase {

    @Autowired BankStatementImporter importer;
    @Autowired BankReconciliationService reconciliationService;
    @Autowired BankReconciliationRepository reconciliationRepository;
    @Autowired BankImportBatchRepository batchRepository;
    @Autowired RapprochementBancaireService rapprochementService;
    @Autowired RapprochementAutomatiqueService automatiqueService;
    @Autowired LigneReleveRepository ligneReleveRepository;
    @Autowired BankLineageService lineageService;

    private BankStatementImporter.DemandeImport demande(UUID compteId) {
        return new BankStatementImporter.DemandeImport(compteId, "Releve test", null, null, null, null, null);
    }

    private ReleveBancaireDto importerEtRapprocher(Dossier d, UUID compteId, MockMultipartFile fichier) {
        return rapprochementService.importer(d.id(), new ImporterReleveRequest(compteId, "Releve test", null, null, null, null), fichier, d.expert());
    }

    private LigneReleve transaction(UUID id) {
        return ligneReleveRepository.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------------------------------ import

    @Test
    void reimporterLeMemeReleve_neCreeAucunDoublon_etLeLotEstTrace() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 5000, LocalDate.now(), "Reglement A");
        MockMultipartFile fichier = csv(LocalDate.now() + ";VIR STATION XYZ;-5000", LocalDate.now() + ";FRAIS TENUE DE COMPTE;-1500");

        var premier = importer.importer(d.id(), demande(r.compteBanqueId()), fichier, d.expert());
        var second = importer.importer(d.id(), demande(r.compteBanqueId()), fichier, d.expert());

        assertThat(premier.releve()).isNotNull();
        assertThat(premier.lot().getImportedRows()).isEqualTo(2);
        assertThat(premier.lot().getImportedBy()).isEqualTo(d.expert().getId());
        assertThat(premier.lot().getFileHash()).hasSize(64);
        assertThat(second.releve()).isNull();
        assertThat(second.lot().getStatus()).isEqualTo(BankImportBatch.NOTHING_NEW);
        assertThat(second.lot().getExactDuplicates()).isEqualTo(2);
        assertThat(second.apercu().dejaImporteIntegralement()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from ligne_releve where entreprise_id = ?", Long.class, d.id())).isEqualTo(2);
        assertThat(batchRepository.findByEntrepriseIdOrderByImportedAtDesc(d.id())).hasSize(2);
        assertThat(evenements("BANK_STATEMENT_IMPORTED")).isNotEmpty();
    }

    @Test
    void apercu_decritCeQuiSeraImporte_sansRienEcrire() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 5000, LocalDate.now(), "Reglement A");
        importer.importer(d.id(), demande(r.compteBanqueId()), csv(LocalDate.now() + ";VIR STATION XYZ;-5000"), d.expert());
        long avant = jdbc.queryForObject("select count(*) from ligne_releve where entreprise_id = ?", Long.class, d.id());
        MockMultipartFile fichier = csv(LocalDate.now() + ";VIR STATION XYZ;-5000",   // deja importee
                LocalDate.now() + ";PRLV SDE;-12000",                                   // nouvelle
                "pas-une-date;KO;100");                                                  // rejetee

        var apercu = importer.apercu(d.id(), demande(r.compteBanqueId()), fichier, d.expert());

        assertThat(apercu.lignesTotal()).isEqualTo(3);
        assertThat(apercu.valides()).isEqualTo(2);
        assertThat(apercu.invalides()).isEqualTo(1);
        assertThat(apercu.rejets()).hasSize(1);
        assertThat(apercu.doublonsExacts()).isEqualTo(1);
        assertThat(apercu.aImporter()).isEqualTo(1);
        assertThat(apercu.totalDebits()).isEqualByComparingTo("17000");
        assertThat(apercu.devise()).isEqualTo("XOF");
        assertThat(apercu.debut()).isEqualTo(LocalDate.now());
        assertThat(jdbc.queryForObject("select count(*) from ligne_releve where entreprise_id = ?", Long.class, d.id())).isEqualTo(avant);
    }

    @Test
    void chevauchement_seulesLesNouvellesTransactionsSontImportees() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 5000, LocalDate.now(), "Reglement A");
        importer.importer(d.id(), demande(r.compteBanqueId()), csv(LocalDate.now() + ";LIGNE UN;-100", LocalDate.now() + ";LIGNE DEUX;-200"), d.expert());

        var suite = importer.importer(d.id(), demande(r.compteBanqueId()),
                csv(LocalDate.now() + ";LIGNE DEUX;-200", LocalDate.now() + ";LIGNE TROIS;-300"), d.expert());

        assertThat(suite.lot().getImportedRows()).isEqualTo(1);
        assertThat(suite.lot().getExactDuplicates()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ligne_releve where entreprise_id = ?", Long.class, d.id())).isEqualTo(3);
    }

    @Test
    void deuxLignesIdentiquesDansLeMemeFichier_sontDeuxOperations_etLeMontantSeulNeFaitPasUnDoublon() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 5000, LocalDate.now(), "Reglement A");
        String jour = LocalDate.now().toString();

        var premier = importer.importer(d.id(), demande(r.compteBanqueId()),
                csv(jour + ";PRLV ASSURANCE;-8000", jour + ";PRLV ASSURANCE;-8000", jour + ";AUTRE LIBELLE;-8000"), d.expert());
        var rejoue = importer.importer(d.id(), demande(r.compteBanqueId()),
                csv(jour + ";PRLV ASSURANCE;-8000", jour + ";PRLV ASSURANCE;-8000", jour + ";AUTRE LIBELLE;-8000"), d.expert());

        assertThat(premier.lot().getImportedRows()).isEqualTo(3);
        assertThat(rejoue.releve()).isNull();
        assertThat(rejoue.lot().getExactDuplicates()).isEqualTo(3);
    }

    @Test
    void memeJourMemeMontantAutreLibelle_estUnDoublonPossible_importeEtMarque() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 5000, LocalDate.now(), "Reglement A");
        String jour = LocalDate.now().toString();
        importer.importer(d.id(), demande(r.compteBanqueId()), csv(jour + ";VIR ALPHA;-4000"), d.expert());

        var suite = importer.importer(d.id(), demande(r.compteBanqueId()), csv(jour + ";VIR BETA;-4000"), d.expert());

        assertThat(suite.apercu().doublonsPossibles()).isEqualTo(1);
        assertThat(suite.releve().getLignes().get(0).getDuplicateStatus()).isEqualTo("POSSIBLE_DUPLICATE");
        assertThat(suite.lot().getImportedRows()).isEqualTo(1);
    }

    @Test
    void ligneInvalide_estRapporteeDansLeLot_jamaisIgnoreeEnSilence() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 5000, LocalDate.now(), "Reglement A");

        var res = importer.importer(d.id(), demande(r.compteBanqueId()), csv(LocalDate.now() + ";OK;-100", "xx;KO;abc"), d.expert());

        assertThat(res.lot().getRejectedRows()).isEqualTo(1);
        assertThat(res.lot().getRejections()).hasSize(1);
        assertThat(res.lot().getRejections().get(0).get("raison").toString()).contains("Date illisible");
    }

    @Test
    void soldes_leControleDEquilibreSeFaitQuandLesDeuxExistent() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 5000, LocalDate.now(), "Reglement A");
        String jour = LocalDate.now().toString();
        MockMultipartFile fichier = csv(jour + ";ENTREE;30000", jour + ";SORTIE;-10000");

        var coherent = importer.apercu(d.id(), new BankStatementImporter.DemandeImport(r.compteBanqueId(), null, null, null,
                new BigDecimal("100000"), new BigDecimal("120000"), null), fichier, d.expert());
        var incoherent = importer.apercu(d.id(), new BankStatementImporter.DemandeImport(r.compteBanqueId(), null, null, null,
                new BigDecimal("100000"), new BigDecimal("125000"), null), fichier, d.expert());
        var sansSoldes = importer.apercu(d.id(), demande(r.compteBanqueId()), fichier, d.expert());

        assertThat(coherent.soldesCoherents()).isTrue();
        assertThat(incoherent.soldesCoherents()).isFalse();
        assertThat(incoherent.ecartSolde()).isEqualByComparingTo("-5000");
        assertThat(sansSoldes.soldesCoherents()).isNull();
    }

    @Test
    void compteDUneAutreEntreprise_estIntrouvable() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        Reglement ra = reglement(a, 5000, LocalDate.now(), "Reglement A");

        assertThatThrownBy(() -> importer.importer(b.id(), demande(ra.compteBanqueId()), csv(LocalDate.now() + ";X;-1"), b.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
        assertThatThrownBy(() -> importer.apercu(b.id(), demande(ra.compteBanqueId()), csv(LocalDate.now() + ";X;-1"), b.expert()))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ------------------------------------------------------------------------------------------ rapprochement

    @Test
    void exact_leSystemeRapprocheSeul_avecScoreEtTrace() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 25000, LocalDate.now(), "Reglement Station XYZ");

        ReleveBancaireDto releve = importerEtRapprocher(d, r.compteBanqueId(), csv(LocalDate.now() + ";VIR STATION XYZ;-25000"));

        var ligne = releve.lignes().get(0);
        assertThat(ligne.pointee()).isTrue();
        assertThat(ligne.ligneEcritureId()).isEqualTo(r.ligneBanqueId());
        BankReconciliation rec = reconciliationRepository.findConfirmeeDe(ligne.id()).orElseThrow();
        assertThat(rec.getSource()).isEqualTo(SourceReconciliation.SYSTEM_EXACT);
        assertThat(rec.getDecision()).isEqualTo(DecisionReconciliation.EXACT);
        assertThat(rec.getConfidence()).isGreaterThanOrEqualTo(new BigDecimal("65"));
        assertThat(rec.getExplanation().get("explication").toString()).contains("Montant exact").contains("Meme tiers");
        assertThat(evenements("BANK_TRANSACTION_MATCHED_AUTOMATICALLY")).isNotEmpty();
        // Lineage : la transaction est lisible depuis l'ecriture du reglement (jamais liee directement a une facture).
        var lineage = lineageService.depuisEcriture(d.id(), r.ecritureId(), d.expert());
        assertThat(lineage.transactions()).hasSize(1);
        assertThat(lineage.transactions().get(0).montant()).isEqualByComparingTo("-25000");
        assertThat(lineage.transactions().get(0).source()).isEqualTo("SYSTEM_EXACT");
        Dossier autre = nouveauDossier(false);
        assertThatThrownBy(() -> lineageService.depuisEcriture(autre.id(), r.ecritureId(), autre.expert())).isInstanceOf(ResponseStatusException.class);
        // Operation saine : aucun constat.
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNMATCHED_PAYMENT, ControlCodes.AMBIGUOUS_BANK_MATCH,
                ControlCodes.MATCH_TO_CONFIRM))).isEmpty();
    }

    @Test
    void probable_estPropose_confirme_puisAnnule_sansJamaisEtreAutomatique() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 40000, LocalDate.now(), "Reglement Station XYZ");
        // Date de la transaction a 10 jours de l'ecriture : meme montant mais date eloignee = probable.
        ReleveBancaireDto releve = importerEtRapprocher(d, r.compteBanqueId(), csv(LocalDate.now().plusDays(10) + ";VIR STATION XYZ;-40000"));
        UUID txId = releve.lignes().get(0).id();

        assertThat(releve.lignes().get(0).pointee()).isFalse();
        BankReconciliation proposition = reconciliationRepository.findActifsDe(List.of(txId)).get(0);
        assertThat(proposition.getStatus()).isEqualTo(StatutReconciliation.PROPOSED);
        assertThat(proposition.getSource()).isEqualTo(SourceReconciliation.SYSTEM_PROPOSAL);
        assertThat(transaction(txId).getLigneEcritureId()).isNull();
        assertThat(evenements("BANK_MATCH_PROPOSED")).isNotEmpty();
        // Le constat « a confirmer » existe.
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.MATCH_TO_CONFIRM))).isNotEmpty();

        BankReconciliation confirmee = reconciliationService.confirmer(d.id(), proposition.getId(), d.expert());
        BankReconciliation encore = reconciliationService.confirmer(d.id(), proposition.getId(), d.expert()); // double confirmation

        assertThat(confirmee.getStatus()).isEqualTo(StatutReconciliation.CONFIRMED);
        assertThat(confirmee.getConfirmedBy()).isEqualTo(d.expert().getId());
        assertThat(encore.getId()).isEqualTo(confirmee.getId());
        assertThat(transaction(txId).getLigneEcritureId()).isEqualTo(r.ligneBanqueId());
        assertThat(reconciliationRepository.findAll().stream().filter(x -> x.getBankTransactionId().equals(txId)
                && x.getStatus() == StatutReconciliation.CONFIRMED)).hasSize(1);

        assertThatThrownBy(() -> reconciliationService.annuler(d.id(), txId, " ", d.expert())).isInstanceOf(ResponseStatusException.class);
        BankReconciliation annulee = reconciliationService.annuler(d.id(), txId, "Mauvaise ecriture", d.expert());

        assertThat(annulee.getStatus()).isEqualTo(StatutReconciliation.REVERSED);
        assertThat(annulee.getReversedBy()).isEqualTo(d.expert().getId());
        assertThat(transaction(txId).getLigneEcritureId()).isNull();
        assertThat(reconciliationRepository.findById(annulee.getId())).isPresent(); // jamais supprime
        assertThat(reconciliationService.annuler(d.id(), txId, "encore", d.expert())).isNull(); // idempotent
        // La decision humaine est respectee : rejouer le matching ne re-rapproche jamais ce qu'un humain vient de defaire.
        automatiqueService.appliquerExacts(entrepriseRepository.findById(d.id()).orElseThrow());
        assertThat(transaction(txId).getLigneEcritureId()).isNull();
        assertThat(evenements("BANK_MATCH_CONFIRMED")).isNotEmpty();
        assertThat(evenements("BANK_RECONCILIATION_REVERSED")).isNotEmpty();
    }

    @Test
    void propositionRejetee_estMemorisee_etNeReviendraPas() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 40000, LocalDate.now(), "Reglement Station XYZ");
        ReleveBancaireDto releve = importerEtRapprocher(d, r.compteBanqueId(), csv(LocalDate.now().plusDays(10) + ";VIR STATION XYZ;-40000"));
        UUID txId = releve.lignes().get(0).id();
        BankReconciliation proposition = reconciliationRepository.findActifsDe(List.of(txId)).get(0);

        BankReconciliation rejetee = reconciliationService.rejeter(d.id(), proposition.getId(), "Ce n'est pas lui", d.expert());
        automatiqueService.appliquerExacts(entrepriseRepository.findById(d.id()).orElseThrow()); // le matching est rejoue

        assertThat(rejetee.getStatus()).isEqualTo(StatutReconciliation.REJECTED);
        assertThat(reconciliationRepository.findActifsDe(List.of(txId))).isEmpty();
        assertThat(transaction(txId).getLigneEcritureId()).isNull();
        assertThat(evenements("BANK_MATCH_REJECTED")).isNotEmpty();
    }

    @Test
    void rejouerLeMatching_neCreeNiDoubleRapprochementNiDoubleProposition() {
        Dossier d = nouveauDossier(false);
        Reglement exact = reglement(d, 25000, LocalDate.now(), "Reglement A");
        Reglement probable = reglement(d, 31000, LocalDate.now(), "Reglement B");
        importerEtRapprocher(d, exact.compteBanqueId(), csv(LocalDate.now() + ";VIR A;-25000", LocalDate.now().plusDays(9) + ";VIR B;-31000"));

        for (int i = 0; i < 3; i++) {
            automatiqueService.appliquerExacts(entrepriseRepository.findById(d.id()).orElseThrow());
        }

        assertThat(jdbc.queryForObject("select count(*) from bank_reconciliation where entreprise_id = ? and status = 'CONFIRMED'", Long.class, d.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from bank_reconciliation where entreprise_id = ? and status = 'PROPOSED'", Long.class, d.id())).isEqualTo(1);
    }

    @Test
    void ambigu_deuxReglementsIdentiques_aucunN_estChoisi_etUnConstatEstOuvert() {
        Dossier d = nouveauDossier(false);
        Reglement un = reglement(d, 18000, LocalDate.now(), "Reglement premier");
        reglement(d, 18000, LocalDate.now(), "Reglement second");

        ReleveBancaireDto releve = importerEtRapprocher(d, un.compteBanqueId(), csv(LocalDate.now() + ";VIR DIVERS;-18000"));

        assertThat(releve.lignes().get(0).pointee()).isFalse();
        assertThat(reconciliationRepository.findActifsDe(List.of(releve.lignes().get(0).id()))).isEmpty();
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        var constats = findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.AMBIGUOUS_BANK_MATCH));
        assertThat(constats).hasSize(1);
        assertThat(constats.get(0).getEvidenceJson().get("candidats").toString()).contains("score");
    }

    @Test
    void ambigu_laReferenceDeLaFacturePeutDepartagerLegitimement() {
        Dossier d = nouveauDossier(false);
        Reglement a = reglement(d, 18000, LocalDate.now(), "Reglement facture FAC-2026-184");
        reglement(d, 18000, LocalDate.now(), "Reglement facture FAC-2026-185");

        ReleveBancaireDto releve = importerEtRapprocher(d, a.compteBanqueId(), csv(LocalDate.now() + ";VIR STATION XYZ REF FAC-2026-184;-18000"));

        assertThat(releve.lignes().get(0).pointee()).isTrue();
        assertThat(releve.lignes().get(0).ligneEcritureId()).isEqualTo(a.ligneBanqueId());
        assertThat(reconciliationRepository.findConfirmeeDe(releve.lignes().get(0).id()).orElseThrow().getExplanation().get("explication").toString())
                .contains("Reference FAC-2026-184 retrouvee");
    }

    @Test
    void sansCorrespondance_remonteEnConstat_etRienNEstInvente() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 5000, LocalDate.now(), "Reglement A");

        ReleveBancaireDto releve = importerEtRapprocher(d, r.compteBanqueId(), csv(LocalDate.now() + ";PAIEMENT MYSTERE;-777000"));

        assertThat(releve.lignes().get(0).pointee()).isFalse();
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNMATCHED_PAYMENT))).isNotEmpty();
        // Aucune ecriture n'est creee a partir d'une transaction non expliquee.
        assertThat(jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ?", Long.class, d.id())).isEqualTo(1);
    }

    @Test
    void uneEcritureDejaRapprocheeNEstPasReprise_etLeChoixManuelEstTrace() {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 12000, LocalDate.now(), "Reglement A");
        ReleveBancaireDto releve = importerEtRapprocher(d, r.compteBanqueId(),
                csv(LocalDate.now() + ";VIR A;-12000", LocalDate.now().plusDays(20) + ";VIR AUTRE;-99000"));
        UUID prise = releve.lignes().stream().filter(l -> l.pointee()).findFirst().orElseThrow().id();
        UUID libre = releve.lignes().stream().filter(l -> !l.pointee()).findFirst().orElseThrow().id();

        assertThatThrownBy(() -> reconciliationService.pointer(d.id(), libre, r.ligneBanqueId(), SourceReconciliation.USER, d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("deja rapprochee");
        assertThatThrownBy(() -> reconciliationService.pointer(d.id(), prise, UUID.randomUUID(), SourceReconciliation.USER, d.expert()))
                .isInstanceOf(ResponseStatusException.class);

        // Annuler puis rapprocher a la main l'autre transaction : choix humain trace.
        reconciliationService.annuler(d.id(), prise, "Erreur", d.expert());
        BankReconciliation manuel = reconciliationService.pointer(d.id(), libre, r.ligneBanqueId(), SourceReconciliation.USER, d.expert());
        assertThat(manuel.getDecision()).isEqualTo(DecisionReconciliation.MANUAL);
        assertThat(manuel.getSource()).isEqualTo(SourceReconciliation.USER);
        assertThat(manuel.getConfirmedBy()).isEqualTo(d.expert().getId());
        assertThat(reconciliationService.pointer(d.id(), libre, r.ligneBanqueId(), SourceReconciliation.USER, d.expert()).getId()).isEqualTo(manuel.getId());
    }

    @Test
    void isolationMultiTenant_uneTransactionEtSesRapprochementsSontInvisiblesPourUneAutreEntreprise() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        Reglement ra = reglement(a, 9000, LocalDate.now(), "Reglement A");
        Reglement rb = reglement(b, 9000, LocalDate.now(), "Reglement B");
        ReleveBancaireDto releveA = importerEtRapprocher(a, ra.compteBanqueId(), csv(LocalDate.now().plusDays(12) + ";VIR;-9000"));
        UUID txA = releveA.lignes().get(0).id();
        BankReconciliation propositionA = reconciliationRepository.findActifsDe(List.of(txA)).get(0);

        assertThatThrownBy(() -> reconciliationService.pointer(b.id(), txA, rb.ligneBanqueId(), SourceReconciliation.USER, b.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
        assertThatThrownBy(() -> reconciliationService.confirmer(b.id(), propositionA.getId(), b.expert())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> reconciliationService.rejeter(b.id(), propositionA.getId(), "x", b.expert())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> reconciliationService.annuler(b.id(), txA, "x", b.expert())).isInstanceOf(ResponseStatusException.class);
        // Une transaction de A ne peut pas viser une ecriture de B depuis A non plus.
        assertThatThrownBy(() -> reconciliationService.pointer(a.id(), txA, rb.ligneBanqueId(), SourceReconciliation.USER, a.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
        assertThat(batchRepository.findByEntrepriseIdOrderByImportedAtDesc(b.id())).isEmpty();
    }

    @Test
    void confirmationsConcurrentes_neCreentQuUnRapprochement() throws Exception {
        Dossier d = nouveauDossier(false);
        Reglement r = reglement(d, 40000, LocalDate.now(), "Reglement A");
        ReleveBancaireDto releve = importerEtRapprocher(d, r.compteBanqueId(), csv(LocalDate.now().plusDays(10) + ";VIR A;-40000"));
        UUID txId = releve.lignes().get(0).id();
        UUID propositionId = reconciliationRepository.findActifsDe(List.of(txId)).get(0).getId();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var depart = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<UUID> tache = () -> {
            depart.await();
            return reconciliationService.confirmer(d.id(), propositionId, d.expert()).getId();
        };
        var f1 = pool.submit(tache);
        var f2 = pool.submit(tache);
        depart.countDown();

        assertThat(f1.get()).isEqualTo(f2.get());
        pool.shutdown();
        assertThat(jdbc.queryForObject("select count(*) from bank_reconciliation where bank_transaction_id = ? and status = 'CONFIRMED'", Long.class, txId)).isEqualTo(1);
    }
}
