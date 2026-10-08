package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.vat.VatDtos.*;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G3A.1 — invariants de robustesse, sur une vraie base PostgreSQL avec de VRAIES transactions concurrentes : version agrégée de la période (+1 par mutation fonctionnelle), pas de lost update
 * (classification, ajustement, crédit, paiement), idempotence du paiement (séquentielle et concurrente), frontière FINAL / règlement, snapshot jamais modifié par un paiement. Cadres : fixtures TEST_ONLY.
 */
class VatG3A1HardeningTest extends VatTestBase {

    @org.springframework.beans.factory.annotation.Autowired com.example.comptable.cabinet.workspace.CabinetWorkspaceService cabinet;

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- outils

    private record Issue(Object resultat, Throwable erreur) {
        int statut() {
            return erreur instanceof ResponseStatusException r ? r.getStatusCode().value() : -1;
        }
    }

    /** Lance deux actions EN MÊME TEMPS (barrière) dans deux transactions distinctes et renvoie leurs issues. */
    private List<Issue> concurrentes(Callable<?> a, Callable<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier depart = new CyclicBarrier(2);
        try {
            List<Future<Issue>> futurs = new ArrayList<>();
            for (Callable<?> c : List.of(a, b)) {
                futurs.add(pool.submit(() -> {
                    depart.await(10, TimeUnit.SECONDS);
                    try {
                        return new Issue(c.call(), null);
                    } catch (Throwable e) {
                        return new Issue(null, e);
                    }
                }));
            }
            List<Issue> issues = new ArrayList<>();
            for (Future<Issue> f : futurs) {
                try {
                    issues.add(f.get(120, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            return issues;
        } finally {
            pool.shutdownNow();
        }
    }

    private long version(Dossier d, UUID periode) {
        return tva.detail(d.expert(), d.id(), periode).version();
    }

    private long nombre(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static PaymentRequest paiement(String montant, String cle, Long version) {
        return new PaymentRequest(n(montant), LocalDate.now(), "REF-" + cle, "BANK_STATEMENT", cle, version);
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- modèle de version

    @Test
    void versionAgregee_chaqueMutationFonctionnelleIncrementeExactementUneFois_etLesLecturesJamais() {
        Dossier d = dossier(true);
        UUID v1 = vente(d, "F1", "1000", "100");
        UUID v2 = vente(d, "F2", "500", "50");
        UUID v3 = vente(d, "F3", "200", "20");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        UtilisateurPrincipal collab = collaborateur(d);

        long v = version(d, p.id());
        tva.operations(d.expert(), d.id(), p.id(), "SALES", null, 0, 10);
        tva.controles(d.expert(), d.id(), p.id());
        tva.declaration(d.expert(), d.id(), p.id());
        reglement.paiements(d.expert(), d.id(), p.id());
        assertThat(version(d, p.id())).as("les lectures ne changent jamais la version").isEqualTo(v);

        assertThat(tva.decider(collab, d.id(), p.id(), v1, new TreatmentRequest("EXEMPT", null, "Exonération justifiée", v)).version()).as("classification").isEqualTo(v + 1);
        v++;
        assertThat(tva.traiterEnMasse(collab, d.id(), p.id(), new BulkTreatmentRequest(List.of(v2, v3), "OUTPUT_TAX", "Ventes confirmées", v)).period().version()).as("validation en masse").isEqualTo(v + 1);
        v++;
        assertThat(tva.ajouterAjustement(collab, d.id(), p.id(), new AdjustmentRequest("MANUAL", "OUTPUT", n("1"), "Ajustement de test", null, null, v)).version()).as("ajout d'ajustement").isEqualTo(v + 1);
        v++;
        UUID ajustement = tva.ajustements(collab, d.id(), p.id()).get(0).id();
        assertThat(tva.annulerAjustement(collab, d.id(), p.id(), ajustement, new CancelRequest("Retrait", v)).version()).as("annulation d'ajustement").isEqualTo(v + 1);
        v++;
        reglement.saisirCredit(collab, d.id(), p.id(), new CreditRequest(n("50"), "Reprise", "Déclaration précédente", null, v));
        assertThat(version(d, p.id())).as("crédit d'ouverture").isEqualTo(v + 1);
        v++;
        assertThat(tva.preparer(collab, d.id(), p.id(), v).version()).as("préparation").isEqualTo(v + 1);
        v++;
        assertThat(tva.revoir(d.expert(), d.id(), p.id(), v).version()).as("revue").isEqualTo(v + 1);
        v++;
        assertThat(tva.rouvrir(collab, d.id(), p.id(), v).version()).as("réouverture").isEqualTo(v + 1);
        v++;
        assertThat(tva.recalculer(collab, d.id(), p.id(), v).version()).as("recalcul sans changement de données").isEqualTo(v);
    }

    @Test
    void versionAgregee_paiementEtAnnulationIncrementent_apresFinalisation() {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        PeriodView finale = finaliser(d, p);
        long v = finale.version();
        PaymentSummary s = reglement.enregistrer(collaborateur(d), d.id(), p.id(), paiement("20", "k1", v));
        assertThat(version(d, p.id())).as("paiement").isEqualTo(v + 1);
        reglement.annuler(collaborateur(d), d.id(), p.id(), s.payments().get(0).id(), new ReverseRequest("Rejeté", v + 1));
        assertThat(version(d, p.id())).as("annulation de paiement").isEqualTo(v + 2);
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- concurrence

    @Test
    void classificationConcurrente_unSeulGagne_l_autreRecoit409_aucuneDecisionPerdueSilencieusement() throws Exception {
        Dossier d = dossier(true);
        UUID v1 = vente(d, "F1", "1000", "100");
        UUID v2 = vente(d, "F2", "500", "50");
        PeriodView p = ouvrir(d);
        long v = version(d, p.id());
        UtilisateurPrincipal a = collaborateur(d);
        UtilisateurPrincipal b = collaborateur(d);

        List<Issue> issues = concurrentes(
                () -> tva.decider(a, d.id(), p.id(), v1, new TreatmentRequest("EXEMPT", null, "Décision de A", v)),
                () -> tva.decider(b, d.id(), p.id(), v2, new TreatmentRequest("EXEMPT", null, "Décision de B", v)));

        assertThat(issues).filteredOn(i -> i.erreur() == null).as("une seule classification aboutit").hasSize(1);
        assertThat(issues).filteredOn(i -> i.statut() == 409).as("l'autre reçoit 409").hasSize(1);
        assertThat(issues.stream().filter(i -> i.erreur() != null).findFirst().orElseThrow().erreur()).hasMessageContaining("modifiée par un autre utilisateur");
        assertThat(nombre("select count(*) from tax_operation_treatment where entreprise_id = ?", d.id())).isEqualTo(1);
        assertThat(version(d, p.id())).isEqualTo(v + 1);

        // B recharge (version courante) puis réussit
        long courante = version(d, p.id());
        UUID restante = jdbc.queryForObject("select e.id from ecriture_comptable e where e.entreprise_id = ? and e.numero_piece in ('F1', 'F2') and not exists (select 1 from tax_operation_treatment t where t.ecriture_id = e.id)", UUID.class, d.id());
        assertThat(tva.decider(b, d.id(), p.id(), restante, new TreatmentRequest("EXEMPT", null, "Décision de B après rechargement", courante)).version()).isEqualTo(courante + 1);
        assertThat(nombre("select count(*) from tax_operation_treatment where entreprise_id = ?", d.id())).isEqualTo(2);
    }

    @Test
    void ajustementConcurrent_unSeulGagne_l_autreRecoit409() throws Exception {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        PeriodView p = ouvrir(d);
        long v = version(d, p.id());
        UtilisateurPrincipal a = collaborateur(d);
        UtilisateurPrincipal b = collaborateur(d);

        List<Issue> issues = concurrentes(
                () -> tva.ajouterAjustement(a, d.id(), p.id(), new AdjustmentRequest("MANUAL", "OUTPUT", n("1"), "Ajustement de A", null, null, v)),
                () -> tva.ajouterAjustement(b, d.id(), p.id(), new AdjustmentRequest("MANUAL", "OUTPUT", n("2"), "Ajustement de B", null, null, v)));

        assertThat(issues).filteredOn(i -> i.erreur() == null).hasSize(1);
        assertThat(issues).filteredOn(i -> i.statut() == 409).hasSize(1);
        assertThat(nombre("select count(*) from tax_adjustment where tax_period_id = ?", p.id())).isEqualTo(1);
        assertThat(version(d, p.id())).isEqualTo(v + 1);
    }

    @Test
    void creditConcurrent_deuxModificationsNeSEcrasentJamaisSilencieusement() throws Exception {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        PeriodView p = ouvrir(d);
        long v = version(d, p.id());
        UtilisateurPrincipal a = collaborateur(d);
        UtilisateurPrincipal b = collaborateur(d);

        List<Issue> issues = concurrentes(
                () -> reglement.saisirCredit(a, d.id(), p.id(), new CreditRequest(n("100"), "Saisie de A", "Source A", null, v)),
                () -> reglement.saisirCredit(b, d.id(), p.id(), new CreditRequest(n("200"), "Saisie de B", "Source B", null, v)));

        assertThat(issues).filteredOn(i -> i.erreur() == null).hasSize(1);
        assertThat(issues).filteredOn(i -> i.statut() == 409).hasSize(1);
        assertThat(nombre("select count(*) from tax_opening_credit where tax_period_id = ?", p.id())).as("une seule ligne : l'autre saisie a été refusée, pas écrasée").isEqualTo(1);
        assertThat(nombre("select count(*) from tax_opening_credit where tax_period_id = ? and superseded_at is null", p.id())).isEqualTo(1);
        assertThat(version(d, p.id())).isEqualTo(v + 1);
    }

    @Test
    void paiementConcurrent_memeCleMemeMontant_unSeulPaiementMetier() throws Exception {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        finaliser(d, p);
        UtilisateurPrincipal a = collaborateur(d);
        UtilisateurPrincipal b = collaborateur(d);

        List<Issue> issues = concurrentes(
                () -> reglement.enregistrer(a, d.id(), p.id(), paiement("30", "CLE-IDENTIQUE", null)),
                () -> reglement.enregistrer(b, d.id(), p.id(), paiement("30", "CLE-IDENTIQUE", null)));

        assertThat(issues).as("les deux appels aboutissent (idempotence), sans erreur").allMatch(i -> i.erreur() == null);
        assertThat(nombre("select count(*) from tax_payment where tax_period_id = ?", p.id())).as("un seul paiement métier").isEqualTo(1);
        PaymentSummary resume = reglement.paiements(d.expert(), d.id(), p.id());
        assertThat(resume.paidAmount()).isEqualByComparingTo("30");
        assertThat(resume.remainingAmount()).isEqualByComparingTo("30");
    }

    @Test
    void paiementConcurrent_clesDifferentes_resteACalculeSousVerrou_jamaisDeDoubleComptage() throws Exception {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        finaliser(d, p);                                                                                  // solde déclaré : 60
        UtilisateurPrincipal a = collaborateur(d);
        UtilisateurPrincipal b = collaborateur(d);

        // sans jeton de version (appel interne) : 40 + 40 dépasserait 60 → exactement un aboutit, l'autre est refusé (reste à payer)
        List<Issue> issues = concurrentes(
                () -> reglement.enregistrer(a, d.id(), p.id(), paiement("40", "CLE-A", null)),
                () -> reglement.enregistrer(b, d.id(), p.id(), paiement("40", "CLE-B", null)));
        assertThat(issues).filteredOn(i -> i.erreur() == null).hasSize(1);
        assertThat(issues).filteredOn(i -> i.statut() == 422).hasSize(1);
        assertThat(reglement.paiements(d.expert(), d.id(), p.id()).paidAmount()).isEqualByComparingTo("40");
        assertThat(reglement.paiements(d.expert(), d.id(), p.id()).remainingAmount()).isEqualByComparingTo("20");

        // avec le même jeton de version (API) : un seul gagne, l'autre reçoit 409
        long v = version(d, p.id());
        List<Issue> avecVersion = concurrentes(
                () -> reglement.enregistrer(a, d.id(), p.id(), paiement("5", "CLE-C", v)),
                () -> reglement.enregistrer(b, d.id(), p.id(), paiement("5", "CLE-D", v)));
        assertThat(avecVersion).filteredOn(i -> i.erreur() == null).hasSize(1);
        assertThat(avecVersion).filteredOn(i -> i.statut() == 409).hasSize(1);
        assertThat(reglement.paiements(d.expert(), d.id(), p.id()).paidAmount()).isEqualByComparingTo("45");
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- idempotence, annulation

    @Test
    void conflitDIdempotence_memeCleAutreMontant_409_aucuneMutationPartielle() {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        finaliser(d, p);
        UtilisateurPrincipal collab = collaborateur(d);
        reglement.enregistrer(collab, d.id(), p.id(), paiement("20", "ABC", null));
        long v = version(d, p.id());
        long lignes = nombre("select count(*) from tax_payment where tax_period_id = ?", p.id());

        assertThatThrownBy(() -> reglement.enregistrer(collab, d.id(), p.id(), paiement("25", "ABC", null))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("idempotence");
        assertThat(nombre("select count(*) from tax_payment where tax_period_id = ?", p.id())).isEqualTo(lignes);
        assertThat(version(d, p.id())).as("aucune version consommée par un refus").isEqualTo(v);
        assertThat(reglement.paiements(collab, d.id(), p.id()).paidAmount()).isEqualByComparingTo("20");

        // rejeu identique : même résultat logique, aucun second paiement, aucune version consommée
        PaymentSummary rejeu = reglement.enregistrer(collab, d.id(), p.id(), paiement("20", "ABC", null));
        assertThat(rejeu.paidAmount()).isEqualByComparingTo("20");
        assertThat(nombre("select count(*) from tax_payment where tax_period_id = ?", p.id())).isEqualTo(lignes);
    }

    @Test
    void annulationDePaiement_tracee_sansDeletePhysique_montantsEtStatutRecalcules_secondeAnnulationRefusee() {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        finaliser(d, p);
        UtilisateurPrincipal collab = collaborateur(d);
        PaymentSummary pleine = reglement.enregistrer(collab, d.id(), p.id(), paiement("60", "PLEIN", null));
        assertThat(pleine.status()).isEqualTo("PAYE");
        UUID id = pleine.payments().get(0).id();

        PaymentSummary apres = reglement.annuler(collab, d.id(), p.id(), id, new ReverseRequest("Virement rejeté par la banque", null));
        assertThat(apres.paidAmount()).isEqualByComparingTo("0");
        assertThat(apres.remainingAmount()).isEqualByComparingTo("60");
        assertThat(apres.status()).isEqualTo("A_PAYER");
        assertThat(nombre("select count(*) from tax_payment where tax_period_id = ?", p.id())).as("aucune suppression physique").isEqualTo(1);
        assertThat(nombre("select count(*) from tax_payment where id = ? and status = 'REVERSED' and reversed_at is not null and reverse_reason is not null", id)).isEqualTo(1);
        long v = version(d, p.id());
        assertThatThrownBy(() -> reglement.annuler(collab, d.id(), p.id(), id, new ReverseRequest("Encore", null))).isInstanceOf(ResponseStatusException.class);
        assertThat(version(d, p.id())).isEqualTo(v);
        assertThat(auditEventRepository.findAll().stream().map(e -> e.getAction()).toList()).contains("PAIEMENT_FISCAL_ANNULE");
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- frontière FINAL / règlement

    @Test
    void apresFinal_lesMutationsDeLaDeclarationSontRefusees_lePaiementResteAutorise_leSnapshotNeBougeJamais() {
        Dossier d = dossier(true);
        UUID v1 = vente(d, "F1", "1000", "100");
        UUID v2 = vente(d, "F2", "500", "50");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        UtilisateurPrincipal collab = collaborateur(d);
        tva.ajouterAjustement(collab, d.id(), p.id(), new AdjustmentRequest("MANUAL", "OUTPUT", n("1"), "Ajustement de test", null, null));
        UUID ajustement = tva.ajustements(collab, d.id(), p.id()).get(0).id();
        tva.annulerAjustement(collab, d.id(), p.id(), ajustement, new CancelRequest("Retiré avant la déclaration"));

        PeriodView finale = finaliser(d, p);
        assertThat(finale.status()).isEqualTo("FINAL");
        SnapshotView snapshot = tva.snapshot(d.expert(), d.id(), p.id());
        String hash = snapshot.sha256();
        Object date = jdbc.queryForMap("select finalized_at, snapshot_id from tax_declaration where tax_period_id = ?", p.id());
        long v = version(d, p.id());

        // la déclaration est figée : aucune mutation de son contenu ni de sa revue
        assertThatThrownBy(() -> tva.decider(collab, d.id(), p.id(), v1, new TreatmentRequest("EXEMPT", null, "Après FINAL", v))).hasMessageContaining("finalisée");
        assertThatThrownBy(() -> tva.traiterEnMasse(collab, d.id(), p.id(), new BulkTreatmentRequest(List.of(v1, v2), "OUTPUT_TAX", "Après FINAL", v))).hasMessageContaining("finalisée");
        assertThatThrownBy(() -> tva.ajouterAjustement(collab, d.id(), p.id(), new AdjustmentRequest("MANUAL", "OUTPUT", n("1"), "Après FINAL", null, null, v))).hasMessageContaining("finalisée");
        assertThatThrownBy(() -> reglement.saisirCredit(collab, d.id(), p.id(), new CreditRequest(n("10"), "Après FINAL", "Source", null, v))).hasMessageContaining("finalisée");
        assertThatThrownBy(() -> tva.preparer(collab, d.id(), p.id(), v)).hasMessageContaining("finalisée");
        assertThatThrownBy(() -> tva.revoir(d.expert(), d.id(), p.id(), v)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tva.rouvrir(d.expert(), d.id(), p.id(), v)).hasMessageContaining("finalisée");
        assertThat(version(d, p.id())).as("aucun refus ne consomme de version").isEqualTo(v);

        // le règlement (post-déclaration) reste autorisé et ne touche ni la déclaration ni le snapshot
        PaymentSummary partiel = reglement.enregistrer(collab, d.id(), p.id(), paiement("20", "K1", v));
        assertThat(partiel.status()).isEqualTo("PARTIELLEMENT_PAYE");
        PeriodView apresPaiement = tva.detail(d.expert(), d.id(), p.id());
        assertThat(apresPaiement.status()).as("statut de déclaration inchangé : FINAL").isEqualTo("FINAL");
        assertThat(apresPaiement.payment().status()).as("statut de règlement indépendant").isEqualTo("PARTIELLEMENT_PAYE");
        PaymentSummary solde = reglement.enregistrer(collab, d.id(), p.id(), paiement("90", "K2", v + 1));
        assertThat(solde.status()).isEqualTo("PAYE");
        assertThat(tva.detail(d.expert(), d.id(), p.id()).status()).as("PAYE n'est jamais un statut de déclaration").isEqualTo("FINAL");
        reglement.annuler(collab, d.id(), p.id(), solde.payments().get(1).id(), new ReverseRequest("Rejeté", v + 2));

        SnapshotView apres = tva.snapshot(d.expert(), d.id(), p.id());
        assertThat(apres.id()).isEqualTo(snapshot.id());
        assertThat(apres.sha256()).as("hash du snapshot inchangé après paiement puis annulation").isEqualTo(hash);
        assertThat(apres.frameworkVersion()).isEqualTo(snapshot.frameworkVersion());
        assertThat(apres.payload()).isEqualTo(snapshot.payload());
        assertThat(jdbc.queryForMap("select finalized_at, snapshot_id from tax_declaration where tax_period_id = ?", p.id())).isEqualTo(date);
        assertThat(nombre("select count(*) from tax_declaration_snapshot where tax_period_id = ?", p.id())).as("jamais de second snapshot").isEqualTo(1);
        assertThatThrownBy(() -> tva.finaliser(d.expert(), d.id(), p.id(), version(d, p.id()))).hasMessageContaining("déjà finalisée");
        assertThat(nombre("select count(*) from tax_declaration_snapshot where tax_period_id = ?", p.id())).isEqualTo(1);
    }

    @Test
    void cabinet_declarationEtReglementRestentDeuxStatuts_libelleComposite() {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        PeriodView finale = finaliser(d, p);
        var requete = new com.example.comptable.cabinet.workspace.CabinetWorkspaceService.DossierQuery(com.example.comptable.cabinet.workspace.CabinetWorkspaceService.Filtre.TOUS, d.entreprise().raisonSociale(),
                null, null, null, null, com.example.comptable.cabinet.workspace.CabinetWorkspaceService.Tri.ENTREPRISE, false, 0, 10);
        var service = cabinet;
        assertThat(service.dossiers(d.expert(), requete).items().get(0).tvaLibelle()).isEqualTo("Finalisée — À payer");
        reglement.enregistrer(collaborateur(d), d.id(), p.id(), paiement("20", "K1", finale.version()));
        assertThat(service.dossiers(d.expert(), requete).items().get(0).tvaLibelle()).isEqualTo("Finalisée — Partiellement payée");
        reglement.enregistrer(collaborateur(d), d.id(), p.id(), paiement("40", "K2", finale.version() + 1));
        assertThat(service.dossiers(d.expert(), requete).items().get(0).tvaLibelle()).isEqualTo("Finalisée — Payée");
        assertThat(tva.detail(d.expert(), d.id(), p.id()).status()).isEqualTo("FINAL");                     // le statut de déclaration reste FINAL
    }
}
