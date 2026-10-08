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
import com.example.comptable.controle.ControlResult;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.ApplicabilityPatch;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.FieldPatch;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.ManualPatch;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.NoteView;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.SectionView;
import com.example.comptable.etatsfinanciers.service.StatementControlPublisher;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotRepository;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotService;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
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
 * Espace de travail des Notes annexes (F8.1) sur PostgreSQL : persistance, separation generees / manuelles, schema strict, statuts, applicabilite, revue, audit, roles, tenant,
 * versionnage optimiste, Findings, snapshot, et scenario de bout en bout avec Note automatique, mixte, manuelle et non applicable jusqu'au DOSSIER_COMPLET FINAL.
 */
class NoteWorkspaceTest extends BankTestBase {

    @Autowired NoteWorkspaceService workspace;
    @Autowired StatementControlPublisher controlPublisher;
    @Autowired FinancialStatementSnapshotService snapshotService;
    @Autowired FinancialStatementSnapshotRepository snapshotRepository;
    @Autowired FinancialStatementDefinitionRegistry registry;
    @Autowired EcritureComptableService ecritureService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    // ------------------------------------------------------------------ outils

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

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return r;
    }

    private void ecrire(Dossier d, Map<String, Integer> debits, Map<String, Integer> credits, CashFlowEvent evenement) {
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        for (var e : debits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "D " + e.getKey(), new BigDecimal(e.getValue()), BigDecimal.ZERO, null));
        }
        for (var e : credits.entrySet()) {
            lignes.add(new LigneEcritureRequest(compte(d, e.getKey()), "C " + e.getKey(), BigDecimal.ZERO, new BigDecimal(e.getValue()), null));
        }
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, LocalDate.now(), "Scenario notes", null, lignes, evenement), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
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

    private NoteView note(Dossier d, String code) {
        return workspace.lire(d.id(), exercice(d), code, d.expert());
    }

    private NoteView patch(Dossier d, UtilisateurPrincipal p, String code, String section, String champ, String valeur) {
        NoteView v = workspace.lire(d.id(), exercice(d), code, p);
        return workspace.modifierDonneesManuelles(d.id(), exercice(d), code, new ManualPatch(v.version(), List.of(new FieldPatch(section, champ, valeur, null, "test")), null), p);
    }

    /** Remplit tous les champs obligatoires manquants d'une note (valeur adaptee au type). */
    private NoteView remplir(Dossier d, UtilisateurPrincipal p, String code) {
        NoteView v = workspace.lire(d.id(), exercice(d), code, p);
        List<FieldPatch> champs = new ArrayList<>();
        for (SectionView s : v.manualSections()) {
            for (var f : s.fields()) {
                if (f.required() && !f.filled()) {
                    champs.add(new FieldPatch(s.sectionCode(), f.fieldCode(), f.type().equals("INTEGER") ? "40" : "Renseigne pour le test", "commentaire", "test"));
                }
            }
        }
        return champs.isEmpty() ? v : workspace.modifierDonneesManuelles(d.id(), exercice(d), code, new ManualPatch(v.version(), champs, null), p);
    }

    private long statements() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
    }

    private static BigDecimal montant(NoteView v, String ligne) {
        return v.generatedData().stream().filter(l -> l.code().equals(ligne)).findFirst().orElseThrow().amountN();
    }

    // ------------------------------------------------------------------ persistance et separation

    @Test
    void creationPersistee_46Notes_versionDeDefinition_etDonneesGenerees() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("612", 100), m("521", 100), null);
        var vue = workspace.lister(d.id(), exercice(d), d.expert());

        assertThat(vue.notes()).hasSize(46);
        assertThat(jdbc.queryForObject("select count(*) from financial_statement_note where entreprise_id = ?", Long.class, d.id())).isEqualTo(46L);
        assertThat(jdbc.queryForObject("select count(*) from financial_statement_note where entreprise_id = ? and framework = 'SYSCOHADA_REVISE_AUDCIF_2017' and definition_version = '2'",
                Long.class, d.id())).isEqualTo(46L);
        var n23 = vue.notes().stream().filter(n -> n.noteCode().equals("23")).findFirst().orElseThrow();
        assertThat(n23.applicabilityStatus()).isEqualTo("APPLICABLE");
        assertThat(n23.completionStatus()).isEqualTo("READY");
        assertThat(montant(n23, "l1")).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select generated_data::text from financial_statement_note where entreprise_id = ? and note_code = '23'", String.class, d.id())).contains("\"lines\"");
        assertThat(n23.frameworkVersion()).isEqualTo("SYSCOHADA_REVISE_AUDCIF_2017/2");
    }

    @Test
    void donneesManuelles_persistees_patchPartiel_etRecalculNeLesEcrasePas() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("601", 300), m("401", 300), null);
        NoteView v = patch(d, d.expert(), "17", "groupe", "contenu", "Aucune dette groupe");
        assertThat(jdbc.queryForObject("select manual_data::text from financial_statement_note where entreprise_id = ? and note_code = '17'", String.class, d.id()))
                .contains("Aucune dette groupe");
        assertThat(v.preparedBy()).isEqualTo(d.expert().getId());

        // patch partiel : l'autre champ s'ajoute sans effacer le premier
        patch(d, d.expert(), "17", "echeances", "contenu", "30 jours");
        NoteView apres = note(d, "17");
        assertThat(apres.manualSections().stream().filter(s -> s.sectionCode().equals("groupe")).findFirst().orElseThrow().fields().get(0).value()).isEqualTo("Aucune dette groupe");
        assertThat(apres.manualSections().stream().filter(s -> s.sectionCode().equals("echeances")).findFirst().orElseThrow().fields().get(0).value()).isEqualTo("30 jours");
        assertThat(montant(apres, "a1")).isEqualByComparingTo("300");

        // recalcul : une nouvelle ecriture change les donnees generees, jamais les donnees manuelles
        ecrire(d, m("601", 200), m("401", 200), null);
        NoteView recalcule = note(d, "17");
        assertThat(montant(recalcule, "a1")).isEqualByComparingTo("500");
        assertThat(recalcule.manualSections().stream().filter(s -> s.sectionCode().equals("groupe")).findFirst().orElseThrow().fields().get(0).value()).isEqualTo("Aucune dette groupe");
        assertThat(recalcule.manualSections().stream().filter(s -> s.sectionCode().equals("echeances")).findFirst().orElseThrow().fields().get(0).value()).isEqualTo("30 jours");
    }

    @Test
    void validationStricte_sectionInconnue_champInconnu_type_sectionGeneree() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        NoteView v35 = note(d, "35");
        for (var patch : List.of(new FieldPatch("inconnue", "contenu", "x", null, null), new FieldPatch("effectif", "inconnu", "x", null, null),
                new FieldPatch("effectif", "effectif", "abc", null, null))) {
            assertThatThrownBy(() -> workspace.modifierDonneesManuelles(d.id(), ex, "35", new ManualPatch(v35.version(), List.of(patch), null), d.expert()))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("422");
        }
        // une section generee n'est pas saisissable
        NoteView v23 = note(d, "23");
        String sectionGeneree = registry.notes().notes().stream().filter(n -> n.code().equals("23")).findFirst().orElseThrow().sections().stream()
                .filter(s -> s.sourceType().equals("GENERATED")).findFirst().orElseThrow().code();
        assertThatThrownBy(() -> workspace.modifierDonneesManuelles(d.id(), ex, "23", new ManualPatch(v23.version(), List.of(new FieldPatch(sectionGeneree, "contenu", "x", null, null)), null),
                d.expert())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("422");
        assertThatThrownBy(() -> workspace.lire(d.id(), ex, "99", d.expert())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    // ------------------------------------------------------------------ statuts

    @Test
    void noteMixte_incomplete_puisReadyApresSaisie() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("601", 300), m("401", 300), null);

        NoteView incomplete = note(d, "17");
        assertThat(incomplete.applicabilityStatus()).isEqualTo("APPLICABLE");
        assertThat(incomplete.completionStatus()).isEqualTo("INCOMPLETE");
        assertThat(incomplete.generatedSections()).isNotEmpty();
        assertThat(incomplete.missingSections()).containsExactlyInAnyOrder("groupe", "echeances");
        assertThat(incomplete.gaps()).anyMatch(g -> g.section().equals("echeances") && g.kind().equals("MANUAL") && g.fieldCodes().contains("contenu"));
        assertThat(montant(incomplete, "a4")).isEqualByComparingTo("300");

        NoteView prete = remplir(d, d.expert(), "17");
        assertThat(prete.completionStatus()).isEqualTo("READY");
        assertThat(prete.missingSections()).isEmpty();
    }

    @Test
    void noteManualRequired_readyApresDecisionEtSaisie() {
        Dossier d = nouveauDossier(false);
        NoteView avant = note(d, "2");
        assertThat(avant.applicabilityStatus()).isEqualTo("TO_DETERMINE");
        assertThat(avant.completionStatus()).isEqualTo("MANUAL_REQUIRED");
        assertThat(avant.generatedData()).isEmpty();

        workspace.modifierApplicabilite(d.id(), exercice(d), "2", new ApplicabilityPatch(avant.version(), "APPLICABLE", "Entite soumise"), d.expert());
        NoteView applicable = note(d, "2");
        assertThat(applicable.completionStatus()).isEqualTo("MANUAL_REQUIRED");
        assertThat(applicable.missingSections()).isNotEmpty();
        assertThat(applicable.applicabilityDecision().humaine()).isTrue();

        assertThat(remplir(d, d.expert(), "2").completionStatus()).isEqualTo("READY");
    }

    @Test
    void schemaPartiel_noteJamaisFinalisable_memeApplicableEtRemplie() {
        Dossier d = nouveauDossier(false);
        NoteView v = note(d, "3A");
        assertThat(v.schemaStatus()).isEqualTo("PARTIAL");
        workspace.modifierApplicabilite(d.id(), exercice(d), "3A", new ApplicabilityPatch(v.version(), "APPLICABLE", null), d.expert());
        NoteView remplie = remplir(d, d.expert(), "3A");
        assertThat(remplie.completionStatus()).isEqualTo("INCOMPLETE");
        assertThat(remplie.gaps()).anyMatch(g -> g.kind().equals("SCHEMA"));
    }

    // ------------------------------------------------------------------ applicabilite

    @Test
    void candidate_neDevientJamaisNonApplicableSansActionExplicite_etBatchValide() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        var vue = workspace.lister(d.id(), ex, d.expert());
        assertThat(vue.notes().stream().filter(n -> n.noteCode().equals("9")).findFirst().orElseThrow().applicabilityStatus()).isEqualTo("NOT_APPLICABLE_CANDIDATE");
        // relectures : toujours candidate
        assertThat(workspace.lister(d.id(), ex, d.expert()).notes().stream().filter(n -> n.noteCode().equals("9")).findFirst().orElseThrow().applicabilityStatus())
                .isEqualTo("NOT_APPLICABLE_CANDIDATE");

        var validee = workspace.validerCandidates(d.id(), ex, d.expert());
        for (String code : List.of("4", "6", "9", "5", "10", "23", "24", "21")) {
            var n = validee.notes().stream().filter(x -> x.noteCode().equals(code)).findFirst().orElseThrow();
            assertThat(n.applicabilityStatus()).as(code).isEqualTo("NOT_APPLICABLE");
            assertThat(n.completionStatus()).as(code).isEqualTo("NOT_APPLICABLE");
            assertThat(n.attached()).as(code).isFalse();
            assertThat(n.applicabilityDecision().decidedBy()).as(code).isEqualTo(d.expert().getId());
        }
        // notes qualitatives : jamais deduites non applicables, meme apres validation en masse
        for (String code : List.of("1", "2", "16B", "16B bis", "16C", "35", "29")) {
            var n = validee.notes().stream().filter(x -> x.noteCode().equals(code)).findFirst().orElseThrow();
            assertThat(n.applicabilityStatus()).as(code).isEqualTo("TO_DETERMINE");
        }
        assertThat(evenements("NOTE_CANDIDATES_VALIDEES")).isNotEmpty();
    }

    @Test
    void note35_effectifInconnu_toDetermine_puisRegleDeSeuil() {
        Dossier d = nouveauDossier(false);
        assertThat(note(d, "35").applicabilityStatus()).isEqualTo("TO_DETERMINE");
        patch(d, d.expert(), "35", "effectif", "effectif", "250");
        NoteView petit = note(d, "35");
        assertThat(petit.applicabilityStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(petit.attached()).isFalse();
        assertThat(petit.applicabilityDecision().humaine()).isFalse();       // derivee de la regle imprimee, pas d'une decision
        patch(d, d.expert(), "35", "effectif", "effectif", "251");
        NoteView grand = note(d, "35");
        assertThat(grand.applicabilityStatus()).isEqualTo("APPLICABLE");
        assertThat(grand.completionStatus()).isEqualTo("MANUAL_REQUIRED");
        // un effectif > 250 interdit de declarer la note non applicable
        assertThatThrownBy(() -> workspace.modifierApplicabilite(d.id(), exercice(d), "35", new ApplicabilityPatch(null, "NOT_APPLICABLE", "x"), d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("422");
    }

    @Test
    void nonApplicable_refuseSiDesDonneesComptablesExistent_etRouvertureAnnuleLaDecision() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("612", 100), m("521", 100), null);
        assertThatThrownBy(() -> workspace.modifierApplicabilite(d.id(), exercice(d), "23", new ApplicabilityPatch(null, "NOT_APPLICABLE", "x"), d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("422");
        NoteView v2 = note(d, "2");
        workspace.modifierApplicabilite(d.id(), exercice(d), "2", new ApplicabilityPatch(v2.version(), "NOT_APPLICABLE", "Sans objet"), d.expert());
        NoteView na = note(d, "2");
        assertThat(na.completionStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(na.applicabilityDecision().reason()).isEqualTo("Sans objet");
        assertThatThrownBy(() -> patch(d, d.expert(), "2", "declaration_de_conformite", "contenu", "x")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        NoteView rouverte = workspace.rouvrir(d.id(), exercice(d), "2", na.version(), d.expert());
        assertThat(rouverte.applicabilityStatus()).isEqualTo("TO_DETERMINE");
        assertThat(evenements("NOTE_ROUVERTE")).isNotEmpty();
        assertThat(evenements("NOTE_APPLICABILITE_MODIFIEE")).isNotEmpty();
    }

    // ------------------------------------------------------------------ revue

    @Test
    void revue_expertSeulement_noteReadySeulement_etModificationInvalideLaRevue() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("612", 100), m("521", 100), null);
        UtilisateurPrincipal collab = collaborateur(d);
        UUID ex = exercice(d);

        // note non READY : revue refusee
        assertThatThrownBy(() -> workspace.relire(d.id(), ex, "2", null, d.expert())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        // collaborateur : pas de revue
        assertThatThrownBy(() -> workspace.relire(d.id(), ex, "23", null, collab)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");

        NoteView revue = workspace.relire(d.id(), ex, "23", null, d.expert());
        assertThat(revue.review().reviewedBy()).isEqualTo(d.expert().getId());
        assertThat(revue.review().valid()).isTrue();
        assertThat(evenements("NOTE_REVUE")).isNotEmpty();

        // une modification posterieure annule la revue
        NoteView v = note(d, "23");
        String section = v.manualSections().get(0).sectionCode();
        workspace.modifierDonneesManuelles(d.id(), ex, "23", new ManualPatch(v.version(), List.of(new FieldPatch(section, "contenu", "commentaire de variation", null, "test")), null), collab);
        NoteView apres = note(d, "23");
        assertThat(apres.review().reviewedAt()).isNull();
        assertThat(apres.review().valid()).isFalse();
        assertThat(evenements("NOTE_REVUE_INVALIDEE")).isNotEmpty();

        // un changement des donnees generees annule aussi la revue
        workspace.relire(d.id(), ex, "23", null, d.expert());
        ecrire(d, m("612", 50), m("521", 50), null);
        assertThat(note(d, "23").review().reviewedAt()).isNull();
        // rouvrir annule la revue
        workspace.relire(d.id(), ex, "23", null, d.expert());
        assertThat(workspace.rouvrir(d.id(), ex, "23", null, d.expert()).review().reviewedAt()).isNull();
    }

    // ------------------------------------------------------------------ findings

    @Test
    void finding_REQUIRED_NOTE_INCOMPLETE_avecChamps_puisAutoResolu() {
        Dossier d = nouveauDossier(false);
        ecrire(d, m("601", 300), m("401", 300), null);
        UUID ex = exercice(d);

        var resultat = controlPublisher.publierNotes(d.id(), ex, d.expert());
        ControlResult c = resultat.controls().stream().filter(x -> x.entityId().endsWith(":17")).findFirst().orElseThrow();
        assertThat(c.code()).isEqualTo(ControlCodes.REQUIRED_NOTE_INCOMPLETE);
        assertThat(c.metadata().get("noteCode")).isEqualTo("17");
        assertThat(c.metadata().get("manques").toString()).contains("section=echeances").contains("fieldCodes=[contenu]").contains("missingInformation");
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.REQUIRED_NOTE_INCOMPLETE))).isNotEmpty();

        remplir(d, d.expert(), "17");
        controlPublisher.publierNotes(d.id(), ex, d.expert());
        // 22 (achats) est aussi applicable avec ventilation manuelle : le finding de la 17 est resolu, celui de la 22 subsiste jusqu'a saisie
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.REQUIRED_NOTE_INCOMPLETE)).stream().map(f -> f.getSourceId()).toList())
                .noneMatch(id -> id.endsWith(":17"));
        remplir(d, d.expert(), "22");
        controlPublisher.publierNotes(d.id(), ex, d.expert());
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.REQUIRED_NOTE_INCOMPLETE))).isEmpty();
    }

    // ------------------------------------------------------------------ roles, tenant, concurrence

    @Test
    void client_peutLire_maisPasModifier_et_tenantEtranger404() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        UUID ex = exercice(a);
        UtilisateurPrincipal client = client(a);

        assertThat(workspace.lister(a.id(), ex, client).notes()).hasSize(46);
        NoteView v = workspace.lire(a.id(), ex, "2", client);
        assertThatThrownBy(() -> workspace.modifierDonneesManuelles(a.id(), ex, "2", new ManualPatch(v.version(), List.of(new FieldPatch("declaration_de_conformite", "contenu", "x", null, null)), null), client))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> workspace.modifierApplicabilite(a.id(), ex, "2", new ApplicabilityPatch(v.version(), "APPLICABLE", null), client))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> workspace.validerCandidates(a.id(), ex, client)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");

        for (var appel : List.<java.util.function.Supplier<?>>of(
                () -> workspace.lister(a.id(), ex, b.expert()),
                () -> workspace.lire(a.id(), ex, "2", b.expert()),
                () -> workspace.modifierDonneesManuelles(a.id(), ex, "2", new ManualPatch(null, List.of(), "x"), b.expert()),
                () -> workspace.modifierApplicabilite(a.id(), ex, "2", new ApplicabilityPatch(null, "APPLICABLE", null), b.expert()),
                () -> workspace.validerCandidates(a.id(), ex, b.expert()),
                () -> workspace.relire(a.id(), ex, "2", null, b.expert()),
                () -> workspace.rouvrir(a.id(), ex, "2", null, b.expert()),
                () -> workspace.lister(b.id(), ex, b.expert()))) {          // exercice d'une autre entreprise
            assertThatThrownBy(appel::get).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
    }

    @Test
    void versionnageOptimiste_deuxUtilisateurs_conflitSurVersionPerimee() {
        Dossier d = nouveauDossier(false);
        UtilisateurPrincipal collab = collaborateur(d);
        UUID ex = exercice(d);
        long versionA = workspace.lire(d.id(), ex, "2", d.expert()).version();                 // A lit
        NoteView parB = workspace.modifierDonneesManuelles(d.id(), ex, "2",
                new ManualPatch(versionA, List.of(new FieldPatch("declaration_de_conformite", "contenu", "Version B", null, null)), null), collab);   // B modifie
        assertThat(parB.version()).isGreaterThan(versionA);

        assertThatThrownBy(() -> workspace.modifierDonneesManuelles(d.id(), ex, "2",
                new ManualPatch(versionA, List.of(new FieldPatch("declaration_de_conformite", "contenu", "Version A", null, null)), null), d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(note(d, "2").manualSections().stream().filter(s -> s.sectionCode().equals("declaration_de_conformite")).findFirst().orElseThrow().fields().get(0).value())
                .isEqualTo("Version B");
        // applicabilite : meme controle
        assertThatThrownBy(() -> workspace.modifierApplicabilite(d.id(), ex, "2", new ApplicabilityPatch(versionA, "APPLICABLE", null), d.expert()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    }

    // ------------------------------------------------------------------ performance

    @Test
    void nombreDeRequetes_listeDes46Notes_borne_etIndependantDuNombreDeComptes() {
        Dossier peu = nouveauDossier(false);
        Dossier beaucoup = nouveauDossier(false);
        ecrire(peu, m("612", 10), m("521", 10), null);
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(beaucoup.id()));
            c.setNumero("6121" + String.format("%03d", i));
            c.setIntitule("Transport " + i);
            c.setLettrable(false);
            c = compteComptableRepository.save(c);
            lignes.add(new LigneEcritureRequest(c.getId(), "Transport " + i, BigDecimal.TEN, BigDecimal.ZERO, null));
        }
        lignes.add(new LigneEcritureRequest(compteComptableRepository.findByEntrepriseIdAndNumero(beaucoup.id(), "521").orElseThrow().getId(), "Banque", BigDecimal.ZERO, new BigDecimal(2000), null));
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, beaucoup.id());
        UUID id = ecritureService.creerManuelle(beaucoup.id(), new CreerEcritureRequest(journal, LocalDate.now(), "200 comptes", null, lignes), beaucoup.expert()).id();
        ecritureService.valider(beaucoup.id(), id, beaucoup.expert());

        UUID exPeu = exercice(peu);
        UUID exBeaucoup = exercice(beaucoup);
        workspace.lister(peu.id(), exPeu, peu.expert());                         // creation des 46 lignes + echauffement
        workspace.lister(beaucoup.id(), exBeaucoup, beaucoup.expert());
        long avant = statements();
        workspace.lister(peu.id(), exPeu, peu.expert());
        long petit = statements() - avant;
        avant = statements();
        workspace.lister(beaucoup.id(), exBeaucoup, beaucoup.expert());
        long grand = statements() - avant;

        assertThat(grand).as("46 notes : 200 comptes vs 1 compte").isEqualTo(petit);
        assertThat(petit).as("aucune requete par note").isLessThan(46);
    }

    // ------------------------------------------------------------------ E2E : DOSSIER_COMPLET FINAL

    @Test
    void e2e_dossierComplet_notesAutomatique_mixte_manuelle_nonApplicable_jusquAuSnapshotFinal() {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        UtilisateurPrincipal collab = collaborateur(d);
        ecrire(d, m("521", 1000), m("101", 1000), CashFlowEvent.CAPITAL_CASH_INCREASE);
        ecrire(d, m("612", 100), m("521", 100), null);          // Note 23 : AUTOMATIC
        ecrire(d, m("601", 300), m("401", 300), null);          // Notes 17 (MIXED) et 22 (MIXED)

        // 1. Note AUTOMATIC READY, revue par l'expert
        assertThat(note(d, "23").completionStatus()).isEqualTo("READY");
        workspace.relire(d.id(), ex, "23", null, d.expert());

        // 2. Notes MIXED : partie generee + saisie manuelle -> READY
        assertThat(note(d, "17").completionStatus()).isEqualTo("INCOMPLETE");
        assertThat(remplir(d, collab, "17").completionStatus()).isEqualTo("READY");
        assertThat(remplir(d, collab, "22").completionStatus()).isEqualTo("READY");

        // 3. Note MANUAL_REQUIRED : decision puis saisie -> READY
        NoteView n2 = note(d, "2");
        workspace.modifierApplicabilite(d.id(), ex, "2", new ApplicabilityPatch(n2.version(), "APPLICABLE", "Entite soumise au SYSCOHADA"), collab);
        assertThat(remplir(d, collab, "2").completionStatus()).isEqualTo("READY");

        // 4. Notes candidates : validation explicite en masse -> NOT_APPLICABLE
        assertThat(note(d, "9").applicabilityStatus()).isEqualTo("NOT_APPLICABLE_CANDIDATE");
        workspace.validerCandidates(d.id(), ex, collab);
        assertThat(note(d, "9").completionStatus()).isEqualTo("NOT_APPLICABLE");

        // 5. le dossier n'est pas finalisable tant que des notes restent a decider ; l'effectif de la Note 35 derive sa non-applicabilite
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Notes");
        patch(d, collab, "35", "effectif", "effectif", "40");
        // decisions explicites sur les notes restantes (aucune n'a de donnee comptable ni d'obligation ici)
        for (NoteView n : workspace.lister(d.id(), ex, d.expert()).notes()) {
            if (n.applicabilityStatus().equals("TO_DETERMINE")) {
                workspace.modifierApplicabilite(d.id(), ex, n.noteCode(), new ApplicabilityPatch(n.version(), "NOT_APPLICABLE", "Sans objet pour cette entite"), collab);
            }
        }
        var vue = workspace.lister(d.id(), ex, d.expert());
        assertThat(vue.statusReasons()).isEmpty();
        assertThat(vue.notes().stream().filter(n -> n.attached()).map(NoteView::noteCode)).containsExactlyInAnyOrder("17", "2", "22", "23");
        assertThat(vue.notes()).allSatisfy(n -> assertThat(n.completionStatus()).isIn("READY", "NOT_APPLICABLE"));

        // 6. cloture, puis finalisation du dossier complet
        clotureService.cloturerAvecConfirmation(d.id(), ex, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test dossier complet avec notes"));
        var snapshot = snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert());

        assertThat(snapshot.status()).isEqualTo("FINAL");
        var payload = snapshot.payload();
        assertThat(payload.get("compteResultat").get("status").asText()).isEqualTo("FINAL");
        assertThat(payload.get("bilanActif").get("status").asText()).isEqualTo("FINAL");
        assertThat(payload.get("tft").get("status").asText()).isEqualTo("FINAL");
        var notes = payload.get("notes");
        assertThat(notes.get("status").asText()).isEqualTo("FINAL");
        assertThat(notes.get("notes").size()).isEqualTo(4);
        assertThat(notes.get("notesNonApplicables").size()).isEqualTo(42);
        assertThat(notes.get("notesNonApplicables").get(0).get("decidedBy").asText()).isNotBlank();
        var n17 = java.util.stream.StreamSupport.stream(notes.get("notes").spliterator(), false).filter(n -> n.get("noteCode").asText().equals("17")).findFirst().orElseThrow();
        assertThat(n17.get("completionStatus").asText()).isEqualTo("READY");
        assertThat(n17.get("definitionVersion").asText()).isEqualTo("2");
        assertThat(n17.get("generatedData").get(0).get("amountN").decimalValue()).isEqualByComparingTo("300");     // donnees generees COPIEES
        assertThat(n17.get("manualData").toString()).contains("Renseigne pour le test");                         // donnees manuelles COPIEES
        var n23 = java.util.stream.StreamSupport.stream(notes.get("notes").spliterator(), false).filter(n -> n.get("noteCode").asText().equals("23")).findFirst().orElseThrow();
        assertThat(n23.get("review").get("reviewedBy").asText()).isEqualTo(d.expert().getId().toString());

        // 7. immuabilite : modifier la note courante ne touche pas le snapshot
        NoteView courante = note(d, "17");
        String section = courante.manualSections().get(0).sectionCode();
        workspace.modifierDonneesManuelles(d.id(), ex, "17", new ManualPatch(courante.version(), List.of(new FieldPatch(section, "contenu", "Modification apres finalisation", null, null)), null), collab);
        var relu = snapshotService.lire(d.id(), snapshot.id(), d.expert());
        assertThat(relu.payloadSha256()).isEqualTo(snapshot.payloadSha256());
        assertThat(relu.payload().toString()).doesNotContain("Modification apres finalisation");
        assertThat(jdbc.queryForObject("select payload_sha256 from financial_statement_snapshot where id = ?", String.class, snapshot.id())).isEqualTo(snapshot.payloadSha256());

        // 8. seconde finalisation, UPDATE, DELETE refuses ; tenant etranger 404
        assertThatThrownBy(() -> snapshotService.finaliser(d.id(), ex, StatementType.DOSSIER_COMPLET, d.expert())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja finalise");
        assertThatThrownBy(() -> jdbc.update("update financial_statement_snapshot set payload = 'x' where id = ?", snapshot.id())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immuable");
        assertThatThrownBy(() -> jdbc.update("delete from financial_statement_snapshot where id = ?", snapshot.id())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immuable");
        Dossier autre = nouveauDossier(false);
        assertThatThrownBy(() -> snapshotService.lire(autre.id(), snapshot.id(), autre.expert())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> snapshotService.finaliser(autre.id(), ex, StatementType.DOSSIER_COMPLET, autre.expert())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThat(snapshotRepository.findById(snapshot.id()).orElseThrow().getEntrepriseId()).isEqualTo(d.id());
    }
}
