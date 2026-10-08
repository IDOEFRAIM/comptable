package com.example.comptable.etatsfinanciers;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.CashFlowEvent;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.dossier.CashFlowQualificationService;
import com.example.comptable.etatsfinanciers.dossier.FinancialDossierExportService;
import com.example.comptable.etatsfinanciers.dossier.FinancialDossierService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.ApplicabilityPatch;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.FieldPatch;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.ManualPatch;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotService;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
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
 * Dossier financier (F9/F10) : synthese de readiness (finalizable vrai/faux), travail par exception, qualification d'un evenement de tresorerie (validation backend), roles de
 * finalisation, exports BROUILLON et FINAL (depuis le snapshot), multi-tenant.
 */
class FinancialDossierTest extends BankTestBase {

    @Autowired FinancialDossierService dossierService;
    @Autowired FinancialDossierExportService exportService;
    @Autowired CashFlowQualificationService qualificationService;
    @Autowired NoteWorkspaceService workspace;
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

    private UUID ecrire(Dossier d, Map<String, Integer> debits, Map<String, Integer> credits, CashFlowEvent evenement) {
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        for (var e : debits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "D " + e.getKey(), new BigDecimal(e.getValue()), BigDecimal.ZERO, null));
        }
        for (var e : credits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "C " + e.getKey(), BigDecimal.ZERO, new BigDecimal(e.getValue()), null));
        }
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, LocalDate.now(), "Scenario dossier", null, lignes, evenement), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
        return id;
    }

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return r;
    }

    private UUID exercice(Dossier d) {
        return exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
    }

    private UtilisateurPrincipal collaborateur(Dossier d) {
        Tenant tenant = tenantRepository.findById(d.expert().getTenantId()).orElseThrow();
        return new UtilisateurPrincipal(utilisateur(tenant, "COLLABORATEUR", null));
    }

    private UtilisateurPrincipal client(Dossier d) {
        Tenant tenant = tenantRepository.findById(d.expert().getTenantId()).orElseThrow();
        Utilisateur c = utilisateur(tenant, "CLIENT", null);
        utilisateurEntrepriseRepository.save(new UtilisateurEntreprise(c.getId(), d.id()));
        return new UtilisateurPrincipal(c);
    }

    /** Decide les notes restantes (non applicables), valide les candidates puis cloture l'exercice. */
    private void preparerLaFinalisation(Dossier d) {
        UUID ex = exercice(d);
        workspace.validerCandidates(d.id(), ex, d.expert());
        for (var n : workspace.lister(d.id(), ex, d.expert()).notes()) {
            if (n.applicabilityStatus().equals("TO_DETERMINE")) {
                workspace.modifierApplicabilite(d.id(), ex, n.noteCode(), new ApplicabilityPatch(n.version(), "NOT_APPLICABLE", "Sans objet"), d.expert());
            }
        }
        clotureService.cloturerAvecConfirmation(d.id(), ex, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test dossier financier"));
    }

    // ------------------------------------------------------------------ synthese

    @Test
    void synthese_exerciceOuvert_nonFinalisable_exceptionsEtChecklist() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("521", 700), m("101", 700), null);                // capital non qualifie : TFT INCOMPLETE
        ecrire(d, m("404", 250), m("521", 250), null);               // 404 : compte reserve non mappe

        var s = dossierService.synthese(d.id(), exercice(d), d.expert());

        assertThat(s.finalizable()).isFalse();
        assertThat(s.finalised()).isFalse();
        assertThat(s.exerciseStatus()).isEqualTo("OUVERT");
        assertThat(s.balanceSheet().status()).isEqualTo("INCOMPLETE");
        assertThat(s.cashFlow().status()).isEqualTo("INCOMPLETE");
        assertThat(s.incomeStatement().status()).isEqualTo("READY");
        assertThat(s.exceptions()).extracting(e -> e.code()).contains("EXERCICE_OUVERT", ControlCodes.UNMAPPED_ACCOUNT, ControlCodes.UNQUALIFIED_CASH_EVENT, "NOTES_A_DECIDER");
        var compte = s.exceptions().stream().filter(e -> e.code().equals(ControlCodes.UNMAPPED_ACCOUNT) && e.tab().equals("BILAN")).findFirst().orElseThrow();
        assertThat(compte.title()).contains("404900");
        assertThat(s.blockingFindings()).isEqualTo((int) s.exceptions().stream().filter(e -> e.severity().equals("BLOCKING")).count()).isPositive();
        assertThat(s.checklist()).extracting(c -> c.code() + ":" + c.status()).contains("EXERCICE:BLOCKED", "COMPTE_RESULTAT:OK", "BILAN:BLOCKED", "TFT:BLOCKED", "NOTES:BLOCKED");
        assertThat(s.balanceCheck().equilibre()).isFalse();
        assertThat(s.notes().total()).isEqualTo(46);
        assertThat(s.permissions().finalise()).isTrue();
        assertThat(s.snapshot()).isNull();
    }

    @Test
    void synthese_finalisable_puisFinalisee_unSeulFinal_etRolesRespectes() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        ecrire(d, m("521", 700), m("101", 700), CashFlowEvent.CAPITAL_CASH_INCREASE);
        preparerLaFinalisation(d);

        var avant = dossierService.synthese(d.id(), ex, d.expert());
        assertThat(avant.finalizable()).isTrue();
        assertThat(avant.exerciseStatus()).isEqualTo("CLOTURE");
        assertThat(avant.exceptions()).noneMatch(e -> e.severity().equals("BLOCKING"));
        assertThat(avant.balanceCheck().equilibre()).isTrue();
        assertThat(avant.notes().status()).isEqualTo("READY");

        // roles : ni CLIENT ni COLLABORATEUR ne finalisent
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, collaborateur(d))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, client(d))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThat(dossierService.synthese(d.id(), ex, collaborateur(d)).permissions().finalise()).isFalse();

        var snapshot = snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert());

        var apres = dossierService.synthese(d.id(), ex, d.expert());
        assertThat(apres.finalised()).isTrue();
        assertThat(apres.finalizable()).isFalse();                     // pas de double finalisation
        assertThat(apres.snapshot().id()).isEqualTo(snapshot.id());
        assertThat(apres.snapshot().payloadSha256()).isEqualTo(snapshot.payloadSha256());
        assertThat(apres.snapshot().createdBy()).isNotBlank();
        assertThat(apres.snapshot().liveChangedSinceFinal()).isFalse();
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja finalise");
    }

    // ------------------------------------------------------------------ qualification des evenements de tresorerie

    @Test
    void qualification_validationBackend_incoherentRefuse_coherentResoutLException() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        UUID capital = ecrire(d, m("521", 700), m("101", 700), null);

        var s = dossierService.synthese(d.id(), ex, d.expert());
        var item = s.exceptions().stream().filter(e -> e.code().equals(ControlCodes.UNQUALIFIED_CASH_EVENT)).findFirst().orElseThrow();
        assertThat(item.target().get("ecritureId")).isEqualTo(capital);
        assertThat(item.target().get("lignes")).isNotNull();
        assertThat(qualificationService.evenements()).extracting(o -> o.code()).contains("CAPITAL_CASH_INCREASE", "BORROWING_CASH_RECEIVED", "NO_TFT_FLOW");

        // un evenement incoherent avec les lignes est refuse par le backend, quelle que soit la selection du frontend
        assertThatThrownBy(() -> qualificationService.qualifier(d.id(), capital, CashFlowEvent.BORROWING_CASH_RECEIVED, d.expert())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");
        assertThatThrownBy(() -> qualificationService.qualifier(d.id(), capital, CashFlowEvent.DIVIDEND_CASH_PAID, d.expert())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");

        // roles
        assertThatThrownBy(() -> qualificationService.qualifier(d.id(), capital, CashFlowEvent.CAPITAL_CASH_INCREASE, client(d))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        Dossier autre = nouveauDossier(false);
        assertThatThrownBy(() -> qualificationService.qualifier(autre.id(), capital, CashFlowEvent.CAPITAL_CASH_INCREASE, autre.expert())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        qualificationService.qualifier(d.id(), capital, CashFlowEvent.CAPITAL_CASH_INCREASE, collaborateur(d));
        var apres = dossierService.synthese(d.id(), ex, d.expert());
        assertThat(apres.cashFlow().status()).isEqualTo("READY");
        assertThat(apres.exceptions()).noneMatch(e -> e.code().equals(ControlCodes.UNQUALIFIED_CASH_EVENT));
        assertThat(evenements("ECRITURE_FLUX_TRESORERIE_QUALIFIE")).isNotEmpty();
    }

    @Test
    void qualification_ecritureSansTresorerie_refusee_et_apresFinalisation_409() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        UUID sansTresorerie = ecrire(d, m("22", 100), m("481", 100), null);
        assertThatThrownBy(() -> qualificationService.qualifier(d.id(), sansTresorerie, CashFlowEvent.NO_TFT_FLOW, d.expert())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");

        UUID capital = ecrire(d, m("521", 700), m("101", 700), CashFlowEvent.CAPITAL_CASH_INCREASE);
        ecrire(d, m("481", 100), m("521", 100), CashFlowEvent.TANGIBLE_ASSET_ACQUISITION_CASH_PAID);
        preparerLaFinalisation(d);
        snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert());
        assertThatThrownBy(() -> qualificationService.qualifier(d.id(), capital, null, d.expert())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    @Test
    void qualification_noTftFlow_accepteSurUneEcritureDeTresorerie_resoutLException_maisLeTftNeCacheJamaisLEcart() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        UUID capital = ecrire(d, m("521", 700), m("101", 700), null);
        assertThat(dossierService.synthese(d.id(), ex, d.expert()).exceptions()).anyMatch(e -> e.code().equals(ControlCodes.UNQUALIFIED_CASH_EVENT));

        // l'expert declare explicitement « aucun flux TFT » : l'evenement est enregistre et l'exception « non qualifie » disparait
        var q = qualificationService.qualifier(d.id(), capital, CashFlowEvent.NO_TFT_FLOW, d.expert());
        assertThat(q.cashFlowEvent()).isEqualTo("NO_TFT_FLOW");
        var apres = dossierService.synthese(d.id(), ex, d.expert());
        assertThat(apres.exceptions()).noneMatch(e -> e.code().equals(ControlCodes.UNQUALIFIED_CASH_EVENT));

        // ... mais ce choix ne rend jamais le dossier finalisable a la place du backend : l'encaissement de capital n'est dans aucune ligne, ZA + ZG != ZH reste signale
        assertThat(apres.cashFlow().status()).isNotEqualTo("READY");
        assertThat(apres.finalizable()).isFalse();
        assertThat(evenements("ECRITURE_FLUX_TRESORERIE_QUALIFIE")).isNotEmpty();
    }

    @Test
    void apresFinalisation_uneEcriturePosterieureEstRefusee_etLeSnapshotEtSonExportFinalNeBougentPas() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        ecrire(d, m("521", 700), m("101", 700), CashFlowEvent.CAPITAL_CASH_INCREASE);
        preparerLaFinalisation(d);
        var snapshot = snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert());
        String exportAvant = exportService.exporterFinal(d.id(), snapshot.id(), d.expert());

        // l'exercice clos refuse toute ecriture posterieure : l'etat vivant ne peut pas deriver du snapshot
        assertThatThrownBy(() -> ecrire(d, m("521", 50), m("101", 50), CashFlowEvent.CAPITAL_CASH_INCREASE)).isInstanceOf(RuntimeException.class);
        var apres = dossierService.synthese(d.id(), ex, d.expert());
        assertThat(apres.finalised()).isTrue();
        assertThat(apres.snapshot().payloadSha256()).isEqualTo(snapshot.payloadSha256());
        assertThat(apres.snapshot().liveChangedSinceFinal()).isFalse();
        assertThat(exportService.exporterFinal(d.id(), snapshot.id(), d.expert())).isEqualTo(exportAvant);
        assertThat(snapshotService.lire(d.id(), snapshot.id(), d.expert()).payloadSha256()).isEqualTo(snapshot.payloadSha256());
    }

    // ------------------------------------------------------------------ exports

    @Test
    void export_brouillon_filigrane_et_export_final_depuisLeSnapshot_sansNotesNonApplicables() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        ecrire(d, m("521", 700), m("101", 700), CashFlowEvent.CAPITAL_CASH_INCREASE);

        String brouillon = exportService.exporterBrouillon(d.id(), ex, d.expert());
        assertThat(brouillon).contains("BROUILLON").contains("filigrane").doesNotContain("DOSSIER FINANCIER FINAL");

        preparerLaFinalisation(d);
        var snapshot = snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert());
        String final1 = exportService.exporterFinal(d.id(), snapshot.id(), d.expert());
        assertThat(final1).contains("DOSSIER FINANCIER FINAL").doesNotContain("class=\"filigrane\"").contains("Compte de resultat").contains("Tableau des flux de tresorerie")
                .contains("Aucune note applicable jointe").contains(snapshot.payloadSha256());
        assertThat(final1).doesNotContain("NOT_APPLICABLE");

        // l'export FINAL provient du snapshot : une modification de l'etat vivant ne le change pas
        var note2 = workspace.lire(d.id(), ex, "2", d.expert());
        workspace.modifierApplicabilite(d.id(), ex, "2", new ApplicabilityPatch(note2.version(), "APPLICABLE", "Changement apres finalisation"), d.expert());
        assertThat(exportService.exporterFinal(d.id(), snapshot.id(), d.expert())).isEqualTo(final1);

        Dossier autre = nouveauDossier(false);
        assertThatThrownBy(() -> exportService.exporterFinal(autre.id(), snapshot.id(), autre.expert())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> exportService.exporterBrouillon(autre.id(), ex, autre.expert())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> dossierService.synthese(autre.id(), ex, autre.expert())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void export_noteApplicableJointe_contenuGenereEtManuel() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        ecrire(d, m("521", 1000), m("101", 1000), CashFlowEvent.CAPITAL_CASH_INCREASE);
        ecrire(d, m("612", 100), m("521", 100), null);          // Note 23
        var n23 = workspace.lire(d.id(), ex, "23", d.expert());
        String section = n23.manualSections().get(0).sectionCode();
        workspace.modifierDonneesManuelles(d.id(), ex, "23", new ManualPatch(n23.version(), List.of(new FieldPatch(section, "contenu", "Variation expliquee", null, null)), null), d.expert());
        preparerLaFinalisation(d);
        var snapshot = snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert());

        String html = exportService.exporterFinal(d.id(), snapshot.id(), d.expert());
        assertThat(html).contains("Note 23").contains("Variation expliquee");
        assertThat(exportService.exporterBrouillon(d.id(), ex, d.expert())).contains("BROUILLON");
    }
}
