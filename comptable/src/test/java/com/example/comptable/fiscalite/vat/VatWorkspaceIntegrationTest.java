package com.example.comptable.fiscalite.vat;

import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.DossierSummaryDto;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.WorkItemDto;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService;
import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.service.AccountingAccountMappingService;
import com.example.comptable.comptabilite.parametrage.web.dto.CreerAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.fiscalite.core.TaxFrameworkService;
import com.example.comptable.fiscalite.core.TaxFrameworkService.CreateRequest;
import com.example.comptable.fiscalite.core.TaxFrameworkService.TaxFramework;
import com.example.comptable.fiscalite.core.VatRules;
import com.example.comptable.fiscalite.vat.VatDtos.*;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Espace TVA sur une vraie base PostgreSQL. Les cadres fiscaux de ces tests sont FICTIFS (taux 10 et 5 inventés pour les besoins du test) et propres à chaque cabinet de test : aucun cadre n'est semé par
 * l'application, aucun taux réel n'est utilisé.
 */
class VatWorkspaceIntegrationTest extends CollecteTestBase {

    @Autowired VatWorkspaceService tva;
    @Autowired TaxFrameworkService cadres;
    @Autowired EcritureComptableService ecritureService;
    @Autowired AccountingAccountMappingService mappingService;
    @Autowired CabinetWorkspaceService cabinet;

    private static final LocalDate DEBUT = LocalDate.now().withDayOfMonth(1);
    private static final LocalDate FIN = DEBUT.plusMonths(1).minusDays(1);

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- outils

    private static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    private UUID compte(Dossier d, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseGet(() -> {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(d.id()));
            c.setNumero(numero);
            c.setIntitule("Compte de test " + numero);
            c.setLettrable(false);
            return compteComptableRepository.save(c);
        }).getId();
    }

    /** Dossier dont les comptes de TVA sont configurés (mappings explicites de l'entreprise) ; un cadre FICTIF VERIFIED y est ajouté si demandé. */
    private Dossier dossier(boolean cadreVerifie) {
        Dossier d = nouveauDossier(false);
        mappingService.creer(d.id(), new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_COLLECTEE, null, compte(d, "443900"), null), d.expert());
        mappingService.creer(d.id(), new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_RECUPERABLE, null, compte(d, "445900"), null), d.expert());
        if (cadreVerifie) {
            verifier(d, "TEST-" + UUID.randomUUID().toString().substring(0, 6), null);
        }
        return d;
    }

    private static VatRules regles(VatRules.DueRule echeance) {
        return new VatRules(List.of(new VatRules.Rate("T10", "Taux fictif A", n("10")), new VatRules.Rate("T5", "Taux fictif B", n("5"))), "MONTHLY", echeance, n("1"), n("0.5"), List.of());
    }

    private TaxFramework verifier(Dossier d, String version, VatRules.DueRule echeance) {
        TaxFramework f = cadres.creer(d.expert(), new CreateRequest("CI", "TVA", version, LocalDate.of(2000, 1, 1), null, regles(echeance), List.of("Source fictive de test, aucune valeur réglementaire")));
        return cadres.verifier(d.expert(), f.id());
    }

    private UtilisateurPrincipal collaborateur(Dossier d) {
        Tenant t = tenantRepository.findById(d.expert().getTenantId()).orElseThrow();
        return new UtilisateurPrincipal(utilisateur(t, "COLLABORATEUR", null));
    }

    private UUID poster(Dossier d, LocalDate date, String piece, List<Object[]> lignes) {
        List<LigneEcritureRequest> l = new ArrayList<>();
        for (Object[] x : lignes) {
            String num = (String) x[0];
            BigDecimal debit = (BigDecimal) x[1];
            BigDecimal credit = (BigDecimal) x[2];
            l.add(new LigneEcritureRequest(compte(d, num), (String) x[0], debit, credit, null));
        }
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, date, "Opération " + piece, piece, l, null), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
        return id;
    }

    private UUID vente(Dossier d, String piece, String ht, String taxe) {
        BigDecimal ttc = n(ht).add(n(taxe));
        return poster(d, DEBUT.plusDays(2), piece, List.of(new Object[] {"521900", ttc, BigDecimal.ZERO}, new Object[] {"701900", BigDecimal.ZERO, n(ht)}, new Object[] {"443900", BigDecimal.ZERO, n(taxe)}));
    }

    private UUID achat(Dossier d, String piece, String ht, String taxe) {
        BigDecimal ttc = n(ht).add(n(taxe));
        return poster(d, DEBUT.plusDays(3), piece, List.of(new Object[] {"601900", n(ht), BigDecimal.ZERO}, new Object[] {"445900", n(taxe), BigDecimal.ZERO}, new Object[] {"521900", BigDecimal.ZERO, ttc}));
    }

    private PeriodView ouvrir(Dossier d) {
        return tva.ouvrir(d.expert(), d.id(), new OpenRequest(DEBUT, FIN));
    }

    private List<com.example.comptable.agent.finding.domain.Finding> constats(Dossier d, String code) {
        return findingRepository.findActifsParCodes(d.id(), List.of(code));
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- cadre fiscal

    @Test
    void cadre_draftNeServiraitJamais_verificationExigeSource_versionImmuable_etRoles() {
        Dossier d = nouveauDossier(false);
        TaxFramework draft = cadres.creer(d.expert(), new CreateRequest("ci", "TVA", "V1", LocalDate.of(2000, 1, 1), null, regles(null), List.of()));
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.jurisdiction()).isEqualTo("CI");
        assertThat(draft.definitionHash()).hasSize(64);
        assertThatThrownBy(() -> cadres.verifier(d.expert(), draft.id())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("référence de source");
        assertThatThrownBy(() -> cadres.creer(d.expert(), new CreateRequest("CI", "TVA", "V1", LocalDate.of(2000, 1, 1), null, regles(null), List.of("s")))).hasMessageContaining("nouvelle version");
        assertThatThrownBy(() -> cadres.creer(d.expert(), new CreateRequest("CI", "TVA", "V2", LocalDate.of(2000, 1, 1), null, new VatRules(List.of(), "MONTHLY", null, null, null, List.of()), List.of("s"))))
                .hasMessageContaining("au moins un taux");
        assertThatThrownBy(() -> cadres.creer(d.expert(), new CreateRequest("CI", "IS", "V3", LocalDate.of(2000, 1, 1), null, regles(null), List.of("s")))).hasMessageContaining("Seule la TVA");

        // résolution : un DRAFT est retourné (non vérifié) tant qu'aucun cadre VERIFIED n'existe ; un RETIRED n'est jamais retourné
        var resolution = cadres.resoudre(d.expert().getTenantId(), "CI", "TVA", LocalDate.now()).orElseThrow();
        assertThat(resolution.verifie()).isFalse();
        TaxFramework verifie = verifier(d, "V9", null);
        assertThat(cadres.resoudre(d.expert().getTenantId(), "CI", "TVA", LocalDate.now()).orElseThrow().framework().id()).isEqualTo(verifie.id());
        cadres.retirer(d.expert(), verifie.id());
        assertThat(cadres.resoudre(d.expert().getTenantId(), "CI", "TVA", LocalDate.now()).orElseThrow().verifie()).isFalse();
        assertThat(cadres.resoudre(d.expert().getTenantId(), "BF", "TVA", LocalDate.now())).isEmpty();                    // aucune règle pour une autre juridiction

        UtilisateurPrincipal collab = collaborateur(d);
        assertThatThrownBy(() -> cadres.creer(collab, new CreateRequest("CI", "TVA", "V5", LocalDate.of(2000, 1, 1), null, regles(null), List.of("s")))).hasMessageContaining("expert-comptable");
        assertThatThrownBy(() -> cadres.verifier(collab, draft.id())).hasMessageContaining("expert-comptable");
        assertThat(auditEventRepository.findAll().stream().map(e -> e.getAction()).toList()).contains("CADRE_FISCAL_CREE", "CADRE_FISCAL_VERIFIE", "CADRE_FISCAL_RETIRE");
        // un autre cabinet ne voit ni ne vérifie ce cadre
        Dossier autre = nouveauDossier(false);
        assertThat(cadres.lister(autre.expert())).extracting(TaxFramework::id).doesNotContain(draft.id());
        assertThatThrownBy(() -> cadres.verifier(autre.expert(), draft.id())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- calcul

    @Test
    void periode_calculeLaTvaDepuisLaComptabilite_ventesAchatsEtLineage() {
        Dossier d = dossier(true);
        UUID v1 = vente(d, "F1", "1000", "100");
        vente(d, "F2", "500", "50");
        achat(d, "A1", "400", "40");

        PeriodView p = ouvrir(d);
        assertThat(p.status()).isEqualTo("OPEN");
        assertThat(p.framework().verifie()).isTrue();
        assertThat(p.basis()).isEqualTo("FISCAL_VERIFIED");
        assertThat(p.totals().outputBase()).isEqualByComparingTo("1500");
        assertThat(p.totals().outputVatFiscal()).isEqualByComparingTo("150");
        assertThat(p.totals().inputVatDeductible()).isEqualByComparingTo("40");
        assertThat(p.totals().netDeclared()).isEqualByComparingTo("110");
        assertThat(p.reconciliation().ledgerGap()).isEqualByComparingTo("0");           // les opérations couvrent tout le mouvement des comptes de TVA
        assertThat(p.anomalies()).isZero();

        assertThat(tva.operations(d.expert(), d.id(), p.id(), "OUTPUT", null, 0, 25).items()).hasSize(2);
        assertThat(tva.operations(d.expert(), d.id(), p.id(), "INPUT", null, 0, 25).items()).hasSize(1);
        assertThat(tva.operations(d.expert(), d.id(), p.id(), null, "TO_REVIEW", 0, 25).total()).isZero();

        LineageView origine = tva.origine(d.expert(), d.id(), p.id(), v1);
        assertThat(origine.operation().vatAccounted()).isEqualByComparingTo("100");
        assertThat(origine.ecritureLignes()).hasSize(3);
        DeclarationView declaration = tva.declaration(d.expert(), d.id(), p.id());
        assertThat(declaration.lines()).extracting(l -> l.source()).contains("OUTPUT_VAT", "NET_VAT");
        assertThat(declaration.officialStructure()).isFalse();                            // aucune case officielle n'est inventée
    }

    @Test
    void sansCadreVerifie_montantsComptabilisesSeulement_finalisationImpossible_puisCadreVerifieInvalideLaRevue() {
        Dossier d = dossier(false);
        vente(d, "F1", "1000", "100");
        PeriodView p = ouvrir(d);
        assertThat(p.basis()).isEqualTo("ACCOUNTED_UNVERIFIED");
        assertThat(p.totals().outputVatFiscal()).isNull();
        assertThat(p.totals().outputVatAccounted()).isEqualByComparingTo("100");
        assertThat(p.framework()).isNull();
        assertThat(p.finalisationBlockers()).anyMatch(b -> b.contains("cadre fiscal"));

        // la préparation et la revue restent possibles (le cadre manquant n'empêche pas le travail), la finalisation non
        tva.recalculer(d.expert(), d.id(), p.id());
        assertThat(tva.preparer(d.expert(), d.id(), p.id()).status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(tva.revoir(d.expert(), d.id(), p.id()).status()).isEqualTo("REVIEWED");
        assertThatThrownBy(() -> tva.finaliser(d.expert(), d.id(), p.id())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("cadre fiscal");

        // un cadre vérifié apparaît : les montants changent de base, la revue précédente est annulée
        verifier(d, "TEST-LATE", null);
        PeriodView apres = tva.recalculer(d.expert(), d.id(), p.id());
        assertThat(apres.basis()).isEqualTo("FISCAL_VERIFIED");
        assertThat(apres.status()).isEqualTo("PREPARING");
        assertThatThrownBy(() -> tva.finaliser(d.expert(), d.id(), p.id())).hasMessageContaining("revue non effectuée");
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- TO_REVIEW, écarts, ajustements

    @Test
    void operationNonDeterminable_estToReview_avecConstat_puisDecisionJustifiee() {
        Dossier d = dossier(true);
        UUID mixte = poster(d, DEBUT.plusDays(4), "M1", List.of(new Object[] {"521900", n("1500"), BigDecimal.ZERO}, new Object[] {"601900", n("1000"), BigDecimal.ZERO}, new Object[] {"445900", n("50"), BigDecimal.ZERO},
                new Object[] {"701900", BigDecimal.ZERO, n("2400")}, new Object[] {"443900", BigDecimal.ZERO, n("100")}, new Object[] {"401900", BigDecimal.ZERO, n("50")}));
        PeriodView p = ouvrir(d);
        assertThat(p.toReview()).isEqualTo(1);
        assertThat(constats(d, ControlCodes.TAX_TREATMENT_MISSING)).hasSize(1);
        assertThatThrownBy(() -> tva.preparer(d.expert(), d.id(), p.id())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("à revoir");
        assertThatThrownBy(() -> tva.decider(d.expert(), d.id(), p.id(), mixte, new TreatmentRequest("OUT_OF_SCOPE", null, " "))).hasMessageContaining("justification");
        assertThatThrownBy(() -> tva.decider(d.expert(), d.id(), p.id(), mixte, new TreatmentRequest("TO_REVIEW", null, "x"))).hasMessageContaining("pas une décision");

        PeriodView apres = tva.decider(collaborateur(d), d.id(), p.id(), mixte, new TreatmentRequest("OUT_OF_SCOPE", null, "Opération hors champ selon le dossier client"));
        assertThat(apres.toReview()).isZero();
        assertThat(constats(d, ControlCodes.TAX_TREATMENT_MISSING)).isEmpty();           // auto-résolution du constat
        assertThat(tva.preparer(d.expert(), d.id(), p.id()).status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(auditEventRepository.findAll().stream().filter(e -> "TRAITEMENT_FISCAL_MODIFIE".equals(e.getAction())).count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void decisionsIncompatiblesAvecLeSens_etPartDeductibleValidee() {
        Dossier d = dossier(true);
        UUID v = vente(d, "F1", "1000", "100");
        UUID a = achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        assertThatThrownBy(() -> tva.decider(d.expert(), d.id(), p.id(), v, new TreatmentRequest("INPUT_TAX", null, "x"))).hasMessageContaining("incompatible");
        assertThatThrownBy(() -> tva.decider(d.expert(), d.id(), p.id(), a, new TreatmentRequest("OUTPUT_TAX", null, "x"))).hasMessageContaining("incompatible");
        assertThatThrownBy(() -> tva.decider(d.expert(), d.id(), p.id(), a, new TreatmentRequest("PARTIALLY_DEDUCTIBLE", n("120"), "x"))).hasMessageContaining("pourcentage");
        PeriodView partielle = tva.decider(d.expert(), d.id(), p.id(), a, new TreatmentRequest("PARTIALLY_DEDUCTIBLE", n("50"), "Usage mixte justifié par le dossier"));
        assertThat(partielle.totals().inputVatAccounted()).isEqualByComparingTo("40");     // comptabilisée
        assertThat(partielle.totals().inputVatDeductible()).isEqualByComparingTo("20");    // fiscalement déductible
        PeriodView non = tva.decider(d.expert(), d.id(), p.id(), a, new TreatmentRequest("NON_DEDUCTIBLE", null, "Non déductible : dépense exclue"));
        assertThat(non.totals().inputVatDeductible()).isEqualByComparingTo("0");
        assertThat(tva.operations(d.expert(), d.id(), p.id(), "INPUT", null, 0, 10).items().get(0).treatmentSource()).isEqualTo("DECISION");
    }

    @Test
    void ecartDeMontant_constatDansLaFile_puisAjustement_autoResolution_annulation_etIdempotence() {
        Dossier d = dossier(true);
        UUID v = vente(d, "F1", "1000", "103");                                              // 10,3 % : proche du taux 10, écart 3 > tolérance 1
        PeriodView p = ouvrir(d);

        assertThat(constats(d, ControlCodes.VAT_AMOUNT_MISMATCH)).hasSize(1);
        assertThat(p.totals().unexplained()).isEqualByComparingTo("3");
        List<WorkItemDto> file = cabinet.workQueue(d.expert(), false, 0, 100).items().stream().filter(i -> i.entrepriseId().equals(d.id())).toList();
        assertThat(file).anySatisfy(i -> {
            assertThat(i.domaine()).isEqualTo("Fiscalité");
            assertThat(i.cible()).isEqualTo("FISCALITE");
            assertThat(i.impact()).isEqualTo("À corriger avant la déclaration");
        });

        // recalculs répétés : aucun doublon de constat
        tva.recalculer(d.expert(), d.id(), p.id());
        tva.recalculer(d.expert(), d.id(), p.id());
        assertThat(constats(d, ControlCodes.VAT_AMOUNT_MISMATCH)).hasSize(1);

        assertThatThrownBy(() -> tva.ajouterAjustement(d.expert(), d.id(), p.id(), new AdjustmentRequest("CORRECTION", "OUTPUT", n("3"), "  ", null, v))).hasMessageContaining("justification");
        assertThatThrownBy(() -> tva.ajouterAjustement(d.expert(), d.id(), p.id(), new AdjustmentRequest("CORRECTION", "OUTPUT", BigDecimal.ZERO, "x", null, v))).hasMessageContaining("nul");
        assertThatThrownBy(() -> tva.ajouterAjustement(d.expert(), d.id(), p.id(), new AdjustmentRequest("INVENTE", "OUTPUT", n("3"), "x", null, v))).hasMessageContaining("Genre");

        PeriodView apres = tva.ajouterAjustement(collaborateur(d), d.id(), p.id(), new AdjustmentRequest("CORRECTION", "OUTPUT", n("3"), "Arrondi commercial accepté par le client", "Facture F1", v));
        assertThat(constats(d, ControlCodes.VAT_AMOUNT_MISMATCH)).isEmpty();               // auto-résolution
        assertThat(apres.totals().unexplained()).isEqualByComparingTo("0");
        assertThat(apres.totals().declaredOutput()).isEqualByComparingTo("103");
        var resolu = jdbc.queryForList("select status, resolution_type from finding where entreprise_id = ? and control_code = ?", d.id(), ControlCodes.VAT_AMOUNT_MISMATCH);
        assertThat(resolu).isNotEmpty().allSatisfy(r -> assertThat(r.get("status")).isEqualTo("RESOLVED"));
        List<AdjustmentView> ajustements = tva.ajustements(d.expert(), d.id(), p.id());
        assertThat(ajustements).hasSize(1);
        tva.recalculer(d.expert(), d.id(), p.id());
        assertThat(tva.ajustements(d.expert(), d.id(), p.id())).hasSize(1);                // un recalcul ne crée jamais d'ajustement

        // annulation : le constat revient (un seul)
        PeriodView annule = tva.annulerAjustement(d.expert(), d.id(), p.id(), ajustements.get(0).id(), new CancelRequest("Ajustement erroné"));
        assertThat(annule.totals().unexplained()).isEqualByComparingTo("3");
        assertThat(constats(d, ControlCodes.VAT_AMOUNT_MISMATCH)).hasSize(1);
        assertThat(tva.ajustements(d.expert(), d.id(), p.id()).get(0).cancelled()).isTrue();       // jamais supprimé

        List<String> actions = auditEventRepository.findAll().stream().map(e -> e.getAction()).toList();
        assertThat(actions).contains("AJUSTEMENT_FISCAL_AJOUTE", "AJUSTEMENT_FISCAL_ANNULE");
        // la comptabilité n'a jamais été modifiée
        assertThat(jdbc.queryForObject("select count(*) from ecriture_comptable where id = ? and statut = 'VALIDEE'", Long.class, v)).isEqualTo(1L);
    }

    @Test
    void anomalieDetecteeProactivement_apresLOuvertureDeLaPeriode_sansRecalculExplicite() {
        Dossier d = dossier(true);
        PeriodView p = ouvrir(d);
        assertThat(constats(d, ControlCodes.VAT_AMOUNT_MISMATCH)).isEmpty();

        vente(d, "F1", "1000", "103");                                                       // une écriture validée APRÈS l'ouverture : aucun appel au workspace TVA
        assertThat(constats(d, ControlCodes.VAT_AMOUNT_MISMATCH)).hasSize(1);
        assertThat(tva.detail(d.expert(), d.id(), p.id()).status()).isEqualTo("PREPARING");

        vente(d, "F2", "1000", "100");                                                       // hors période : ne concerne pas la période ouverte
        jdbc.update("update tax_period set period_end = period_end where id = ?", p.id());
        assertThat(constats(d, ControlCodes.VAT_AMOUNT_MISMATCH)).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- cycle, snapshot, immutabilité

    @Test
    void cycleComplet_preparation_revue_finalisation_snapshotImmuable() {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        UtilisateurPrincipal collab = collaborateur(d);

        assertThatThrownBy(() -> tva.revoir(d.expert(), d.id(), p.id())).hasMessageContaining("READY_FOR_REVIEW");        // pas avant la préparation
        assertThat(tva.preparer(collab, d.id(), p.id()).status()).isEqualTo("READY_FOR_REVIEW");
        assertThatThrownBy(() -> tva.revoir(collab, d.id(), p.id())).hasMessageContaining("expert-comptable");
        assertThatThrownBy(() -> tva.finaliser(collab, d.id(), p.id())).hasMessageContaining("expert-comptable");
        assertThatThrownBy(() -> tva.finaliser(d.expert(), d.id(), p.id())).hasMessageContaining("revue");               // pas de finalisation sans revue

        // une modification après la préparation ramène à PREPARING et annule la revue
        PeriodView modifie = tva.ajouterAjustement(collab, d.id(), p.id(), new AdjustmentRequest("MANUAL", "OUTPUT", n("1"), "Ajustement de test", null, null));
        assertThat(modifie.status()).isEqualTo("PREPARING");
        tva.annulerAjustement(collab, d.id(), p.id(), tva.ajustements(collab, d.id(), p.id()).get(0).id(), new CancelRequest("Retiré"));
        assertThat(tva.preparer(collab, d.id(), p.id()).status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(tva.revoir(d.expert(), d.id(), p.id()).status()).isEqualTo("REVIEWED");

        PeriodView finale = tva.finaliser(d.expert(), d.id(), p.id());
        assertThat(finale.status()).isEqualTo("FINAL");
        assertThat(finale.totals().netDeclared()).isEqualByComparingTo("60");
        SnapshotView snapshot = tva.snapshot(d.expert(), d.id(), p.id());
        assertThat(snapshot.sha256()).hasSize(64);
        assertThat(snapshot.payload()).containsKeys("framework", "period", "totals", "declaration", "adjustments", "controls", "reviewer", "finalizedAt", "lines");
        assertThat(((java.util.Map<?, ?>) snapshot.payload().get("framework")).get("definitionHash")).isNotNull();
        assertThat(auditEventRepository.findAll().stream().map(e -> e.getAction()).toList()).contains("DECLARATION_FISCALE_REVUE", "DECLARATION_FISCALE_FINALISEE");

        // immuabilité : le snapshot ne peut être ni modifié ni supprimé (base), la période ni modifiée ni refinalisée, une nouvelle écriture ne change rien
        assertThatThrownBy(() -> jdbc.update("update tax_declaration_snapshot set payload = '{}' where tax_period_id = ?", p.id())).isInstanceOf(DataAccessException.class).hasMessageContaining("immuable");
        assertThatThrownBy(() -> jdbc.update("delete from tax_declaration_snapshot where tax_period_id = ?", p.id())).isInstanceOf(DataAccessException.class).hasMessageContaining("immuable");
        assertThatThrownBy(() -> tva.finaliser(d.expert(), d.id(), p.id())).hasMessageContaining("déjà finalisée");
        assertThatThrownBy(() -> tva.ajouterAjustement(d.expert(), d.id(), p.id(), new AdjustmentRequest("MANUAL", "OUTPUT", n("1"), "x", null, null))).hasMessageContaining("immuable");
        assertThatThrownBy(() -> tva.rouvrir(d.expert(), d.id(), p.id())).hasMessageContaining("immuable");
        vente(d, "F9", "5000", "500");
        assertThat(tva.detail(d.expert(), d.id(), p.id()).totals().netDeclared()).isEqualByComparingTo("60");              // relu depuis le snapshot
        assertThat(tva.declaration(d.expert(), d.id(), p.id()).status()).isEqualTo("FINAL");
        assertThat(constats(d, ControlCodes.TAX_PERIOD_INCOMPLETE)).isEmpty();            // finalisation : constats de la période résolus
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- droits, tenant, échéances, cabinet

    @Test
    void clientRefuse_autreCabinet404_periodeChevauchante409() {
        Dossier d = dossier(true);
        PeriodView p = ouvrir(d);
        UtilisateurPrincipal client = new UtilisateurPrincipal(utilisateur(tenantRepository.findById(d.expert().getTenantId()).orElseThrow(), "CLIENT", null));
        assertThatThrownBy(() -> tva.ouvrir(client, d.id(), new OpenRequest(DEBUT.minusMonths(3), DEBUT.minusMonths(2)))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tva.preparer(client, d.id(), p.id())).isInstanceOf(ResponseStatusException.class);

        Dossier autre = nouveauDossier(false);
        assertThatThrownBy(() -> tva.detail(autre.expert(), d.id(), p.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tva.recalculer(autre.expert(), d.id(), p.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tva.lister(autre.expert(), d.id())).isInstanceOf(ResponseStatusException.class);
        // une période de l'entreprise A n'est pas lisible via l'entreprise B du même cabinet
        assertThatThrownBy(() -> tva.detail(d.expert(), autre.id(), p.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tva.ouvrir(d.expert(), d.id(), new OpenRequest(DEBUT.plusDays(5), FIN))).hasMessageContaining("chevauche");
        assertThatThrownBy(() -> tva.ouvrir(d.expert(), d.id(), new OpenRequest(FIN, DEBUT))).hasMessageContaining("Période invalide");
    }

    @Test
    void echeance_generee_seulementParUneRegleVerifiee_jamaisCodeeEnDur() {
        Dossier sans = dossier(true);                                                         // cadre vérifié SANS règle d'échéance
        ouvrir(sans);
        assertThat(jdbc.queryForObject("select count(*) from dossier_echeance where entreprise_id = ? and type = 'DECLARATION_FISCALE'", Long.class, sans.id())).isZero();

        Dossier avec = dossier(false);
        verifier(avec, "TEST-ECH", new VatRules.DueRule(1, 20));                              // règle fictive : le 20 du mois suivant
        ouvrir(avec);
        LocalDate attendue = FIN.plusMonths(1).withDayOfMonth(Math.min(20, FIN.plusMonths(1).lengthOfMonth()));
        assertThat(jdbc.queryForObject("select date_echeance from dossier_echeance where entreprise_id = ? and type = 'DECLARATION_FISCALE'", LocalDate.class, avec.id())).isEqualTo(attendue);

        Dossier brouillon = dossier(false);                                                   // cadre DRAFT : jamais d'échéance
        cadres.creer(brouillon.expert(), new CreateRequest("CI", "TVA", "D1", LocalDate.of(2000, 1, 1), null, regles(new VatRules.DueRule(1, 20)), List.of("s")));
        ouvrir(brouillon);
        assertThat(jdbc.queryForObject("select count(*) from dossier_echeance where entreprise_id = ? and type = 'DECLARATION_FISCALE'", Long.class, brouillon.id())).isZero();
    }

    @Test
    void cabinet_workQueueEtPortefeuille_voientLaTvaAPreparerPuisAReviser() {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "103");
        PeriodView p = ouvrir(d);
        DossierSummaryDto avant = cabinet.dossiers(d.expert(), new CabinetWorkspaceService.DossierQuery(CabinetWorkspaceService.Filtre.TOUS, d.entreprise().raisonSociale(), null, null, null, null,
                CabinetWorkspaceService.Tri.ENTREPRISE, false, 0, 10)).items().get(0);
        assertThat(avant.tva()).isEqualTo("A_TRAITER");
        assertThat(avant.tvaLibelle()).isEqualTo("1 à traiter");

        tva.ajouterAjustement(d.expert(), d.id(), p.id(), new AdjustmentRequest("CORRECTION", "OUTPUT", n("3"), "Écart accepté", null, null));
        // l'ajustement n'est pas lié à l'opération : l'écart reste ; on lie l'ajustement à l'opération
        UUID op = tva.operations(d.expert(), d.id(), p.id(), null, null, 0, 5).items().get(0).ecritureId();
        for (AdjustmentView a : tva.ajustements(d.expert(), d.id(), p.id())) {
            tva.annulerAjustement(d.expert(), d.id(), p.id(), a.id(), new CancelRequest("Refait avec lien"));
        }
        tva.ajouterAjustement(d.expert(), d.id(), p.id(), new AdjustmentRequest("CORRECTION", "OUTPUT", n("3"), "Écart accepté", null, op));
        tva.preparer(d.expert(), d.id(), p.id());

        DossierSummaryDto apres = cabinet.dossiers(d.expert(), new CabinetWorkspaceService.DossierQuery(CabinetWorkspaceService.Filtre.TOUS, d.entreprise().raisonSociale(), null, null, null, null,
                CabinetWorkspaceService.Tri.ENTREPRISE, false, 0, 10)).items().get(0);
        assertThat(apres.tvaLibelle()).isEqualTo("À revoir");
        assertThat(cabinet.workQueue(d.expert(), false, 0, 100).items()).anySatisfy(i -> {
            assertThat(i.entrepriseId()).isEqualTo(d.id());
            assertThat(i.kind()).isEqualTo("TAX_REVIEW");
            assertThat(i.titre()).contains("Déclaration TVA prête pour revue");
            assertThat(i.cible()).isEqualTo("FISCALITE");
        });
        tva.revoir(d.expert(), d.id(), p.id());
        assertThat(cabinet.workQueue(d.expert(), false, 0, 100).items()).noneMatch(i -> i.entrepriseId().equals(d.id()) && "TAX_REVIEW".equals(i.kind()));
    }
}
