package com.example.comptable.etatsfinanciers;

import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.CashFlowEvent;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.etatsfinanciers.dossier.CashFlowQualificationService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.ApplicabilityPatch;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.FieldPatch;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.ManualPatch;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.service.CashFlowStatementService;
import com.example.comptable.etatsfinanciers.service.StatementControlPublisher;
import com.example.comptable.etatsfinanciers.service.StatementResult;
import com.example.comptable.etatsfinanciers.service.StatementResult.Line;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Durcissement final : hash du snapshot (couvre tout le contenu), piste d'audit (acteur, tenant, exercice, horodatage, avant/apres), exercice precedent REEL pour ZA, FB, FC, FD, FE,
 * Finding de flux non qualifie resolu apres qualification.
 */
class HardeningFinalTest extends BankTestBase {

    @Autowired EcritureComptableService ecritureService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired NoteWorkspaceService workspace;
    @Autowired FinancialStatementSnapshotService snapshotService;
    @Autowired CashFlowStatementService cashFlowStatementService;
    @Autowired CashFlowQualificationService qualificationService;
    @Autowired StatementControlPublisher controlPublisher;

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

    private UUID ecrire(Dossier d, LocalDate date, Map<String, Integer> debits, Map<String, Integer> credits, CashFlowEvent evenement) {
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        debits.forEach((k, v) -> lignes.add(new LigneEcritureRequest(compte(d, k), "D " + k, new BigDecimal(v), BigDecimal.ZERO, null)));
        credits.forEach((k, v) -> lignes.add(new LigneEcritureRequest(compte(d, k), "C " + k, BigDecimal.ZERO, new BigDecimal(v), null)));
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, date, "Scenario durcissement", null, lignes, evenement), d.expert()).id();
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

    private static BigDecimal montant(StatementResult etat, String code) {
        return etat.lines().stream().filter(l -> l.code().equals(code)).findFirst().map(Line::amountN).orElseThrow();
    }

    private static String sha256(String texte) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texte.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ N-1 reel

    @Test
    void zaFbFcFdFe_utilisentLExercicePrecedentReel_pasUneAnneeCivile() {
        Dossier d = nouveauDossier(false);
        var exN = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow();
        LocalDate debutN1 = exN.getDateDebut().minusMonths(18);                  // exercice precedent de 18 mois
        jdbc.update("insert into exercice_comptable (entreprise_id, date_debut, date_fin, statut) values (?, ?, ?, 'OUVERT')", d.id(), debutN1, exN.getDateDebut().minusDays(1));
        LocalDate n1 = debutN1.plusDays(20);
        ecrire(d, n1, m("521", 1000), m("101", 1000), CashFlowEvent.CAPITAL_CASH_INCREASE);
        ecrire(d, n1, m("31", 100), m("6031", 100), null);
        ecrire(d, n1, m("411", 200), m("701", 200), null);
        ecrire(d, n1, m("601", 150), m("401", 150), null);
        LocalDate n = LocalDate.now();
        ecrire(d, n, m("31", 40), m("6031", 40), null);                          // stock brut +40       -> FC = -40
        ecrire(d, n, m("521", 120), m("411", 120), null);                        // client paye 120      -> FD = +120
        ecrire(d, n, m("401", 50), m("521", 50), null);                          // fournisseur paye 50  -> FE = -50

        StatementResult tft = cashFlowStatementService.generer(d.id(), exN.getId(), d.expert());

        assertThat(montant(tft, "ZA")).isEqualByComparingTo("1000");              // tresorerie nette de cloture de l'exercice precedent REEL
        assertThat(montant(tft, "FB")).isEqualByComparingTo("0");
        assertThat(montant(tft, "FC")).isEqualByComparingTo("-40");
        assertThat(montant(tft, "FD")).isEqualByComparingTo("120");
        assertThat(montant(tft, "FE")).isEqualByComparingTo("-50");
        assertThat(montant(tft, "ZG")).isEqualByComparingTo("70");
        assertThat(montant(tft, "ZH")).isEqualByComparingTo("1070");
        assertThat(tft.status()).isEqualTo(Status.READY);
    }

    // ------------------------------------------------------------------ finding resolu

    @Test
    void findingFluxNonQualifie_ouvertPuisResoluApresQualification() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        UUID capital = ecrire(d, LocalDate.now(), m("521", 700), m("101", 700), null);

        controlPublisher.publierTft(d.id(), ex, d.expert());
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNQUALIFIED_CASH_EVENT))).hasSize(1);

        qualificationService.qualifier(d.id(), capital, CashFlowEvent.CAPITAL_CASH_INCREASE, d.expert());
        controlPublisher.publierTft(d.id(), ex, d.expert());
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.UNQUALIFIED_CASH_EVENT))).isEmpty();
    }

    // ------------------------------------------------------------------ piste d'audit

    @Test
    void auditTrail_acteurTenantExerciceHorodatageEtAvantApres() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        UUID capital = ecrire(d, LocalDate.now(), m("521", 700), m("101", 700), null);
        ecrire(d, LocalDate.now(), m("601", 300), m("401", 300), null);

        qualificationService.qualifier(d.id(), capital, CashFlowEvent.CAPITAL_CASH_INCREASE, d.expert());
        var n17 = workspace.lire(d.id(), ex, "17", d.expert());
        workspace.modifierDonneesManuelles(d.id(), ex, "17", new ManualPatch(n17.version(), List.of(new FieldPatch("groupe", "contenu", "Aucune dette groupe", null, null)), null), d.expert());
        var n2 = workspace.lire(d.id(), ex, "2", d.expert());
        workspace.modifierApplicabilite(d.id(), ex, "2", new ApplicabilityPatch(n2.version(), "APPLICABLE", "Entite soumise"), d.expert());

        for (String action : List.of("ECRITURE_FLUX_TRESORERIE_QUALIFIE", "NOTE_DONNEES_MANUELLES_MODIFIEES", "NOTE_APPLICABILITE_MODIFIEE")) {
            AuditEvent e = evenements(action).stream().filter(x -> d.expert().getTenantId().equals(x.getTenantId())).findFirst().orElseThrow();
            assertThat(e.getUtilisateurId()).as(action + " : acteur").isEqualTo(d.expert().getId());
            assertThat(e.getTenantId()).as(action + " : tenant").isEqualTo(d.expert().getTenantId());
            assertThat(e.getCreatedAt()).as(action + " : horodatage").isNotNull();
            assertThat(e.getDetails().get("source")).isEqualTo("HUMAIN");
            assertThat(e.getDetails().get("exercice")).as(action + " : exercice").isEqualTo(ex.toString());
        }
        AuditEvent qualif = evenements("ECRITURE_FLUX_TRESORERIE_QUALIFIE").stream().filter(x -> d.expert().getTenantId().equals(x.getTenantId())).findFirst().orElseThrow();
        assertThat(qualif.getDetails().get("avant")).isEqualTo("");
        assertThat(qualif.getDetails().get("apres")).isEqualTo("CAPITAL_CASH_INCREASE");
        AuditEvent applic = evenements("NOTE_APPLICABILITE_MODIFIEE").stream().filter(x -> d.expert().getTenantId().equals(x.getTenantId())).findFirst().orElseThrow();
        assertThat(applic.getDetails().get("avant")).isEqualTo("TO_DETERMINE");
        assertThat(applic.getDetails().get("apres")).isEqualTo("APPLICABLE");
        AuditEvent saisie = evenements("NOTE_DONNEES_MANUELLES_MODIFIEES").stream().filter(x -> d.expert().getTenantId().equals(x.getTenantId())).findFirst().orElseThrow();
        assertThat(saisie.getDetails().toString()).contains("avant").contains("apres").contains("Aucune dette groupe");
    }

    // ------------------------------------------------------------------ concurrence

    /**
     * Revele par le parcours navigateur : l'ecran charge la synthese et les notes en parallele ; deux creations simultanees des 46 lignes de notes violaient la contrainte d'unicite (500).
     */
    @Test
    void chargementsSimultanesDuMemeDossier_neCreentPasDeDoublonsEtReussissentTous() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        ecrire(d, LocalDate.now(), m("601", 300), m("401", 300), null);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Integer>> appels = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int k = i;
                appels.add(pool.submit(() -> k % 2 == 0 ? workspace.lister(d.id(), ex, d.expert()).notes().size() : workspace.lire(d.id(), ex, "17", d.expert()).noteCode().length()));
            }
            for (var a : appels) {
                assertThat(a.get(120, java.util.concurrent.TimeUnit.SECONDS)).isPositive();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("select count(*) from financial_statement_note where entreprise_id = ?", Long.class, d.id())).isEqualTo(46L);
    }

    // ------------------------------------------------------------------ hash du snapshot

    @Test
    void hashDuSnapshot_couvreToutLeContenu_etDetecteToutChangement() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        ecrire(d, LocalDate.now(), m("521", 700), m("101", 700), CashFlowEvent.CAPITAL_CASH_INCREASE);
        workspace.validerCandidates(d.id(), ex, d.expert());
        for (var n : workspace.lister(d.id(), ex, d.expert()).notes()) {
            if (n.applicabilityStatus().equals("TO_DETERMINE")) {
                workspace.modifierApplicabilite(d.id(), ex, n.noteCode(), new ApplicabilityPatch(n.version(), "NOT_APPLICABLE", "Sans objet"), d.expert());
            }
        }
        clotureService.cloturerAvecConfirmation(d.id(), ex, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test hash"));
        var snapshot = snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert());

        String stocke = jdbc.queryForObject("select payload from financial_statement_snapshot where id = ?", String.class, snapshot.id());
        // le hash est celui du contenu stocke, en entier : etats, notes, controles, versions, metadonnees
        assertThat(sha256(stocke)).isEqualTo(snapshot.payloadSha256());
        for (String partie : List.of("\"frameworkVersion\"", "\"definitionHashes\"", "\"entreprise\"", "\"exercice\"", "\"generatedAt\"", "\"compteResultat\"", "\"bilanActif\"", "\"bilanPassif\"",
                "\"tft\"", "\"notes\"", "\"notesNonApplicables\"", "\"controls\"", "\"status\"")) {
            assertThat(stocke).as("contenu fige : " + partie).contains(partie);
        }
        // toute alteration d'un champ, ou l'ajout d'un controle, change l'empreinte
        assertThat(sha256(stocke.replaceFirst("\"status\":\"FINAL\"", "\"status\":\"BROUILLON\""))).isNotEqualTo(snapshot.payloadSha256());
        assertThat(sha256(stocke.replace("\"controls\":[", "\"controls\":[{\"code\":\"X\"},"))).isNotEqualTo(snapshot.payloadSha256());
        assertThat(sha256(stocke.replaceFirst("\"frameworkVersion\":\"[^\"]*\"", "\"frameworkVersion\":\"AUTRE/9\""))).isNotEqualTo(snapshot.payloadSha256());
    }
}
