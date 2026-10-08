package com.example.comptable.etatsfinanciers;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService;
import com.example.comptable.etatsfinanciers.service.CashFlowStatementService;
import com.example.comptable.etatsfinanciers.service.NotesService;
import com.example.comptable.etatsfinanciers.service.StatementControlPublisher;
import com.example.comptable.etatsfinanciers.service.StatementResult;
import com.example.comptable.etatsfinanciers.service.StatementResult.Line;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Phase F (G5 a G9) sur PostgreSQL : TFT par variations (ZA, FC, ZG, ZH, ZA + ZG = ZH), elements non monetaires exclus, TFT incomplet (CAFG partielle, evenements de
 * financement absents), Notes (applicabilite, Note 35 indeterminee, note non applicable non jointe), constats et refus de finalisation.
 */
class PhaseGEtatsFinanciersTest extends BankTestBase {

    @Autowired BalanceSheetService balanceSheetService;
    @Autowired CashFlowStatementService cashFlowStatementService;
    @Autowired NotesService notesService;
    @Autowired StatementControlPublisher controlPublisher;
    @Autowired FinancialStatementSnapshotService snapshotService;
    @Autowired EcritureComptableService ecritureService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired ExerciceComptableRepository exerciceRepository;

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

    /** Ecriture validee et equilibree : debits et credits par prefixe de compte (compte local « prefixe900 »). */
    private void ecrire(Dossier d, Map<String, Integer> debits, Map<String, Integer> credits) {
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        int total = 0;
        for (var e : debits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "D " + e.getKey(), new BigDecimal(e.getValue()), BigDecimal.ZERO, null));
            total += e.getValue();
        }
        int totalCredit = 0;
        for (var e : credits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "C " + e.getKey(), BigDecimal.ZERO, new BigDecimal(e.getValue()), null));
            totalCredit += e.getValue();
        }
        assertThat(total).isEqualTo(totalCredit);
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal(d), LocalDate.now(), "Scenario G", null, lignes), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
    }

    private UUID exercice(Dossier d) {
        return exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
    }

    private static Line ligne(StatementResult etat, String code) {
        return etat.lines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow();
    }

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return r;
    }

    private void verifierInvariants(StatementResult tft) {
        assertThat(ligne(tft, "ZA").amountN().add(ligne(tft, "ZG").amountN())).isEqualByComparingTo(ligne(tft, "ZH").amountN());
    }

    // ------------------------------------------------------------------ TFT

    @Test
    void tft_venteACredit_FDnegatif_ZGegalZero_etInvariantZAplusZGegalZH() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("411", 1000), m("701", 1000));

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        assertThat(ligne(tft, "FA").amountN()).isEqualByComparingTo("1000");     // CAFG = EBE
        assertThat(ligne(tft, "FD").amountN()).isEqualByComparingTo("-1000");    // creance client en hausse
        assertThat(ligne(tft, "ZB").amountN()).isEqualByComparingTo("0");
        assertThat(ligne(tft, "ZA").amountN()).isEqualByComparingTo("0");
        assertThat(ligne(tft, "ZG").amountN()).isEqualByComparingTo("0");
        assertThat(ligne(tft, "ZH").amountN()).isEqualByComparingTo("0");
        assertThat(tft.status()).isEqualTo(Status.READY);
        assertThat(tft.controls()).isEmpty();
        verifierInvariants(tft);
    }

    @Test
    void tft_achatEtStock_FCsurStocksNets_FEsurDettes() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("601", 400), m("401", 400));
        ecrire(d, m("31", 100), m("6031", 100));

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        assertThat(ligne(tft, "FA").amountN()).isEqualByComparingTo("-300");
        assertThat(ligne(tft, "FC").amountN()).isEqualByComparingTo("-100");     // -(BB N - BB N-1)
        assertThat(ligne(tft, "FE").amountN()).isEqualByComparingTo("400");      // dette fournisseur en hausse
        assertThat(ligne(tft, "ZG").amountN()).isEqualByComparingTo("0");
        assertThat(ligne(tft, "ZH").amountN()).isEqualByComparingTo("0");
        assertThat(tft.status()).isEqualTo(Status.READY);
        verifierInvariants(tft);
    }

    @Test
    void tft_depreciationEtDotation_nonMonetaires_sontExclues() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("411", 1000), m("701", 1000));
        ecrire(d, m("691", 100), m("491", 100));      // dotation : ni dans la CAFG (hors EBE) ni dans FD

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        assertThat(ligne(tft, "FA").amountN()).isEqualByComparingTo("1000");
        assertThat(ligne(tft, "FD").amountN()).isEqualByComparingTo("-1000");    // le compte 491 (non monetaire) n'entre pas
        assertThat(ligne(tft, "ZG").amountN()).isEqualByComparingTo("0");
        assertThat(tft.status()).isEqualTo(Status.READY);
        verifierInvariants(tft);
    }

    @Test
    void tft_termeCafgPartial_ou_evenementsDeFinancementAbsents_donneIncompleteSansInvention() {
        Dossier d = nouveauDossier(false);
        // frais financiers (67) : terme PARTIAL de la CAFG ; capital en banque : evenement de financement non qualifiable par le numero de compte
        ecrire(d, m("67", 10), m("521", 10));
        ecrire(d, m("521", 700), m("101", 700));

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        assertThat(tft.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(ligne(tft, "FA").amountN()).isNull();
        assertThat(ligne(tft, "FA").complete()).isFalse();
        assertThat(ligne(tft, "FA").incompleteReasons()).anyMatch(r -> r.contains("PARTIAL"));
        assertThat(ligne(tft, "FK").amountN()).isNull();
        assertThat(ligne(tft, "ZB").amountN()).isNull();
        assertThat(ligne(tft, "ZG").amountN()).isNull();
        assertThat(tft.controls()).extracting(ControlResult::code).doesNotContain(ControlCodes.CASH_FLOW_MISMATCH);
        assertThat(ligne(tft, "ZH").amountN()).isEqualByComparingTo("690");     // tresorerie nette calculable meme si les flux ne le sont pas
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), exercice(d), StatementType.TFT, d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("INCOMPLETE");
    }

    @Test
    void tft_cessionEncaisseeSansEvenementQualifie_estIncomplete_sansEstimation() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("22", 500), m("521", 500));      // acquisition payee comptant : preuve de tresorerie dans l'ecriture
        ecrire(d, m("521", 100), m("22", 100));      // encaissement lie a une sortie : evenement non qualifie

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        assertThat(ligne(tft, "FG").amountN()).isEqualByComparingTo("-500");
        assertThat(ligne(tft, "FI").amountN()).isNull();
        assertThat(ligne(tft, "FJ").amountN()).isNull();
        assertThat(tft.controls()).extracting(ControlResult::code).contains(ControlCodes.UNQUALIFIED_CASH_EVENT);
        assertThat(tft.status()).isEqualTo(Status.INCOMPLETE);
    }

    @Test
    void tft_acquisitionSimple_FGegalDecaissement() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("521", 1000), m("701", 1000));
        ecrire(d, m("22", 300), m("521", 300));

        StatementResult tft = cashFlowStatementService.generer(d.id(), exercice(d), d.expert());

        assertThat(ligne(tft, "FG").amountN()).isEqualByComparingTo("-300");
        assertThat(ligne(tft, "FI").amountN()).isEqualByComparingTo("0");
        assertThat(ligne(tft, "ZH").amountN()).isEqualByComparingTo("700");
        assertThat(ligne(tft, "ZG").amountN()).isEqualByComparingTo("700");
        assertThat(tft.status()).isEqualTo(Status.READY);
        verifierInvariants(tft);
    }

    // ------------------------------------------------------------------ Bilan apres cloture

    @Test
    void bilan_exerciceCloture_resteReady_leResultatReste_enCJ_avantAffectation() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("521", 1000), m("701", 1000));
        UUID ex = exercice(d);
        clotureService.cloturerAvecConfirmation(d.id(), ex, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test bilan apres cloture"));

        var bilan = balanceSheetService.bilan(d.id(), ex, d.expert());

        assertThat(bilan.status()).isEqualTo(Status.READY);
        assertThat(ligne(bilan.passif(), "CJ").amountN()).isEqualByComparingTo("1000");
        assertThat(ligne(bilan.passif(), "CH").amountN()).isEqualByComparingTo("0");
        assertThat(bilan.totalActifN()).isEqualByComparingTo("1000");
        assertThat(bilan.totalPassifN()).isEqualByComparingTo("1000");
    }

    // ------------------------------------------------------------------ Notes

    @Test
    void notes_note35_indeterminee_sansEffectif_nonApplicable_jusqua250_applicable_au_dela() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);

        var sans = notesService.generer(d.id(), ex, NotesService.Decisions.aucune(), d.expert());
        var n35 = sans.notes().stream().filter(n -> n.code().equals("35")).findFirst().orElseThrow();
        assertThat(n35.applicability()).isEqualTo("UNDETERMINED");
        assertThat(n35.attached()).isFalse();
        assertThat(n35.status()).isEqualTo("MANUAL_REQUIRED");
        assertThat(sans.status()).isEqualTo(Status.INCOMPLETE);

        var petit = notesService.generer(d.id(), ex, new NotesService.Decisions(250, Map.of()), d.expert());
        var p35 = petit.notes().stream().filter(n -> n.code().equals("35")).findFirst().orElseThrow();
        assertThat(p35.applicability()).isEqualTo("NOT_APPLICABLE");
        assertThat(p35.attached()).isFalse();
        assertThat(p35.status()).isEqualTo("NOT_APPLICABLE");

        var grand = notesService.generer(d.id(), ex, new NotesService.Decisions(251, Map.of()), d.expert());
        var g35 = grand.notes().stream().filter(n -> n.code().equals("35")).findFirst().orElseThrow();
        assertThat(g35.applicability()).isEqualTo("APPLICABLE");
        assertThat(g35.attached()).isTrue();
        assertThat(g35.status()).isEqualTo("MANUAL_REQUIRED");      // section manuelle requise
        assertThat(g35.gaps()).extracting(NotesService.NoteGap::noteCode).containsOnly("35");
    }

    @Test
    void notes_automatique_note23_calculeeDepuisLesComptes_etNoteSansDonneeNonJointe() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("612", 100, "614", 40), m("521", 140));
        UUID ex = exercice(d);

        var notes = notesService.generer(d.id(), ex, NotesService.Decisions.aucune(), d.expert());

        var n23 = notes.notes().stream().filter(n -> n.code().equals("23")).findFirst().orElseThrow();
        assertThat(n23.applicability()).isEqualTo("APPLICABLE");
        assertThat(n23.attached()).isTrue();
        assertThat(n23.lines().stream().filter(l -> l.code().equals("l1")).findFirst().orElseThrow().amountN()).isEqualByComparingTo("100");
        assertThat(n23.lines().stream().filter(l -> l.code().equals("l6")).findFirst().orElseThrow().amountN()).isEqualByComparingTo("140");
        assertThat(n23.status()).isEqualTo("READY");
        assertThat(n23.completion()).contains("% complete");
        // note 24 : aucune donnee dans son perimetre -> non-applicabilite PROUVEE mais a valider ; jamais jointe vide
        var n24 = notes.notes().stream().filter(n -> n.code().equals("24")).findFirst().orElseThrow();
        assertThat(n24.attached()).isFalse();
        assertThat(n24.applicability()).isEqualTo("NOT_APPLICABLE_CANDIDATE");
        // decision explicite : note non applicable ecartee
        var decidee = notesService.generer(d.id(), ex, new NotesService.Decisions(null, Map.of("23", "NOT_APPLICABLE")), d.expert());
        assertThat(decidee.notes().stream().filter(n -> n.code().equals("23")).findFirst().orElseThrow().attached()).isFalse();
    }

    @Test
    void notes_noteApplicableIncomplete_publieUnFinding_etDossierJamaisFinalise() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("67", 10), m("521", 10));        // Note 29 : section manuelle requise (interets non comptabilises)
        UUID ex = exercice(d);

        var notes = controlPublisher.publierNotes(d.id(), ex, d.expert());

        var n29 = notes.notes().stream().filter(n -> n.code().equals("29")).findFirst().orElseThrow();
        assertThat(n29.attached()).isTrue();
        assertThat(n29.status()).isEqualTo("INCOMPLETE");
        assertThat(n29.gaps()).extracting(NotesService.NoteGap::section).anyMatch(s -> s.contains("int"));
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.REQUIRED_NOTE_INCOMPLETE))).isNotEmpty();
        // idempotent
        controlPublisher.publierNotes(d.id(), ex, d.expert());
        int avant = findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.REQUIRED_NOTE_INCOMPLETE)).size();
        controlPublisher.publierNotes(d.id(), ex, d.expert());
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.REQUIRED_NOTE_INCOMPLETE))).hasSize(avant);

        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex, StatementType.NOTES_R4, d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("INCOMPLETE");
    }

    // ------------------------------------------------------------------ multi-tenant

    @Test
    void isolationMultiTenant_notesEtTft() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        UUID exA = exercice(a);
        for (var appel : List.<java.util.function.Supplier<?>>of(
                () -> notesService.generer(b.id(), exA, NotesService.Decisions.aucune(), b.expert()),
                () -> cashFlowStatementService.generer(b.id(), exA, b.expert()),
                () -> balanceSheetService.bilan(b.id(), exA, b.expert()),
                () -> snapshotService.finaliser(b.id(), exA, StatementType.DOSSIER_COMPLET, b.expert()))) {
            assertThatThrownBy(appel::get).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
    }
}
