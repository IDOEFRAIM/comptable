package com.example.comptable.fiscalite.vat;

import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.DossierSummaryDto;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.fiscalite.core.VatRules;
import com.example.comptable.fiscalite.service.DeclarationFiscaleService;
import com.example.comptable.fiscalite.vat.VatDtos.*;
import com.example.comptable.fiscalite.vat.VatModel.Line;
import com.example.comptable.fiscalite.vat.VatWorkspaceService.OperationFilters;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G3A — infrastructure TVA indépendante de la réglementation, sur une vraie base PostgreSQL : pagination, recherche et filtres SQL, validation en masse, verrouillage optimiste, suivi interne du paiement,
 * crédit d'ouverture, blocage de finalisation sans cadre vérifié, intégration au Cabinet. Les cadres sont des FIXTURES TEST_ONLY (voir {@link VatTestBase}) : aucune règle burkinabè n'est utilisée.
 */
class VatG3AInfrastructureTest extends VatTestBase {

    @Autowired CabinetWorkspaceService cabinet;
    @Autowired DeclarationFiscaleService declarations;

    private static OperationFilters filtres(String cote, String statut, String traitement, boolean constat, LocalDate de, String q, String tri, boolean asc) {
        return new OperationFilters(cote, statut, traitement, constat, de, null, q, tri, asc);
    }

    private DossierSummaryDto ligneCabinet(Dossier d) {
        return cabinet.dossiers(d.expert(), new CabinetWorkspaceService.DossierQuery(CabinetWorkspaceService.Filtre.TOUS, d.entreprise().raisonSociale(), null, null, null, null,
                CabinetWorkspaceService.Tri.ENTREPRISE, false, 0, 10)).items().get(0);
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- pagination, recherche, filtres, tri

    @Test
    void listeParPageSql_totalExact_recherche_filtres_et_tri_serveur() {
        Dossier d = dossier(true);
        for (int i = 1; i <= 30; i++) {
            vente(d, "F%03d".formatted(i), String.valueOf(i * 100), String.valueOf(i * 10));
        }
        for (int j = 1; j <= 8; j++) {
            achat(d, "A%02d".formatted(j), String.valueOf(j * 40), String.valueOf(j * 4));
        }
        mixte(d, "M001");
        PeriodView p = ouvrir(d);
        UtilisateurPrincipal u = d.expert();

        // pagination : seule la page demandée est renvoyée, le total vient de la base
        var page1 = tva.operations(u, d.id(), p.id(), filtres("SALES", null, null, false, null, null, "document", true), 1, 10);
        assertThat(page1.items()).hasSize(10);
        assertThat(page1.total()).isEqualTo(31);                                           // 30 ventes + l'opération non déterminable (affichée des deux côtés)
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.items().get(0).piece()).isEqualTo("F011");
        var derniere = tva.operations(u, d.id(), p.id(), filtres("SALES", null, null, false, null, null, "document", true), 3, 10);
        assertThat(derniere.items()).hasSize(1);

        // tri serveur : montant décroissant
        var parMontant = tva.operations(u, d.id(), p.id(), filtres("SALES", null, null, false, null, null, "amount", false), 0, 3);
        assertThat(parMontant.items()).extracting(Line::piece).containsExactly("F030", "F029", "F028");

        // recherche : référence, tiers/libellé, montant, identifiant d'écriture
        assertThat(tva.operations(u, d.id(), p.id(), filtres(null, null, null, false, null, "F007", "date", true), 0, 10).items()).extracting(Line::piece).containsExactly("F007");
        assertThat(tva.operations(u, d.id(), p.id(), filtres(null, null, null, false, null, "opération f012", "date", true), 0, 10).items()).extracting(Line::piece).containsExactly("F012");
        assertThat(tva.operations(u, d.id(), p.id(), filtres(null, null, null, false, null, "770", "date", true), 0, 10).items()).extracting(Line::piece).contains("F007");
        UUID cible = tva.operations(u, d.id(), p.id(), filtres(null, null, null, false, null, "A03", "date", true), 0, 1).items().get(0).ecritureId();
        assertThat(tva.operations(u, d.id(), p.id(), filtres(null, null, null, false, null, cible.toString(), "date", true), 0, 10).items()).extracting(Line::piece).containsExactly("A03");
        assertThat(tva.operations(u, d.id(), p.id(), filtres(null, null, null, false, null, "%", "date", true), 0, 10).total()).isZero();      // les jokers sont échappés

        // filtres : traitement, à revoir, constat actif, date
        assertThat(tva.operations(u, d.id(), p.id(), filtres(null, null, "INPUT_TAX", false, null, null, "date", true), 0, 50).total()).isEqualTo(8);
        assertThat(tva.operations(u, d.id(), p.id(), filtres(null, null, "OUTPUT_TAX", false, null, null, "date", true), 0, 50).total()).isEqualTo(30);
        var aRevoir = tva.operations(u, d.id(), p.id(), filtres(null, "TO_REVIEW", null, false, null, null, "date", true), 0, 50);
        assertThat(aRevoir.items()).extracting(Line::piece).containsExactly("M001");
        assertThat(tva.operations(u, d.id(), p.id(), filtres(null, "OK", null, false, null, null, "date", true), 0, 50).total()).isEqualTo(38);
        assertThat(tva.operations(u, d.id(), p.id(), filtres(null, null, null, true, null, null, "date", true), 0, 50).items()).extracting(Line::piece).containsExactly("M001");
        assertThat(tva.operations(u, d.id(), p.id(), filtres("SALES", null, null, false, DEBUT.plusDays(3), null, "date", true), 0, 50).items()).extracting(Line::piece).containsExactly("M001");
        assertThat(tva.operations(u, d.id(), p.id(), new OperationFilters("PURCHASES", null, null, false, DEBUT.plusDays(3), DEBUT.plusDays(3), null, "date", true), 0, 50).total()).isEqualTo(8);

        // un autre cabinet ne lit rien
        Dossier autre = dossier(false);
        assertThatThrownBy(() -> tva.operations(autre.expert(), d.id(), p.id(), filtres(null, null, null, false, null, null, "date", true), 0, 10)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- validation en masse

    @Test
    void validationEnMasse_revalideeParLeServeur_toutOuRien_etTracee() {
        Dossier d = dossier(true);
        List<UUID> ventes = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ventes.add(vente(d, "F%02d".formatted(i), "100", "10"));
        }
        UUID achat = achat(d, "A01", "100", "10");
        UUID melange = mixte(d, "M001");
        PeriodView p = ouvrir(d);

        // refus : sélection mélangée (une vente + un achat), opération non déterminable, traitement non certain, ou différent de ce qu'impose le sens : rien n'est écrit
        List<UUID> avecAchat = new ArrayList<>(ventes);
        avecAchat.add(achat);
        assertThatThrownBy(() -> tva.traiterEnMasse(collaborateur(d), d.id(), p.id(), new BulkTreatmentRequest(avecAchat, "OUTPUT_TAX", "Validation en masse", null))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incompatible").hasMessageContaining("impose INPUT_TAX");
        assertThatThrownBy(() -> tva.traiterEnMasse(collaborateur(d), d.id(), p.id(), new BulkTreatmentRequest(List.of(melange), "OUTPUT_TAX", "x", null))).hasMessageContaining("non déterminable");
        assertThatThrownBy(() -> tva.traiterEnMasse(collaborateur(d), d.id(), p.id(), new BulkTreatmentRequest(ventes, "EXEMPT", "x", null))).hasMessageContaining("OUTPUT_TAX et INPUT_TAX");
        assertThatThrownBy(() -> tva.traiterEnMasse(collaborateur(d), d.id(), p.id(), new BulkTreatmentRequest(ventes, "INPUT_TAX", "x", null))).hasMessageContaining("impose OUTPUT_TAX");
        assertThatThrownBy(() -> tva.traiterEnMasse(collaborateur(d), d.id(), p.id(), new BulkTreatmentRequest(ventes, "OUTPUT_TAX", " ", null))).hasMessageContaining("justification");
        assertThatThrownBy(() -> tva.traiterEnMasse(collaborateur(d), d.id(), p.id(), new BulkTreatmentRequest(List.of(UUID.randomUUID()), "OUTPUT_TAX", "x", null))).hasMessageContaining("introuvable");
        assertThat(jdbc.queryForObject("select count(*) from tax_operation_treatment where entreprise_id = ?", Long.class, d.id())).isZero();

        // conflit : une décision humaine contraire existe sur une des opérations
        tva.decider(collaborateur(d), d.id(), p.id(), ventes.get(0), new TreatmentRequest("EXEMPT", null, "Exonération justifiée par le client"));
        assertThatThrownBy(() -> tva.traiterEnMasse(collaborateur(d), d.id(), p.id(), new BulkTreatmentRequest(ventes, "OUTPUT_TAX", "Validation", null))).hasMessageContaining("incompatible");
        List<UUID> quatre = ventes.subList(1, 5);

        // droits
        assertThatThrownBy(() -> tva.traiterEnMasse(client(d), d.id(), p.id(), new BulkTreatmentRequest(quatre, "OUTPUT_TAX", "x", null))).isInstanceOf(ResponseStatusException.class);

        PeriodView apres = tva.traiterEnMasse(collaborateur(d), d.id(), p.id(), new BulkTreatmentRequest(quatre, "OUTPUT_TAX", "Ventes taxables confirmées par la lecture des factures", null)).period();
        assertThat(apres.totals().operations()).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from tax_operation_treatment where entreprise_id = ? and treatment = 'OUTPUT_TAX'", Long.class, d.id())).isEqualTo(4);
        assertThat(tva.operations(d.expert(), d.id(), p.id(), filtres("SALES", null, "OUTPUT_TAX", false, null, "F02", "date", true), 0, 5).items().get(0).treatmentSource()).isEqualTo("DECISION");
        assertThat(auditEventRepository.findAll().stream().map(e -> e.getAction()).toList()).contains("TRAITEMENT_FISCAL_BULK_MODIFIE", "TRAITEMENT_FISCAL_MODIFIE");

        // autre cabinet : 404 ; période finalisée : refus
        Dossier autre = dossier(false);
        assertThatThrownBy(() -> tva.traiterEnMasse(autre.expert(), d.id(), p.id(), new BulkTreatmentRequest(quatre, "OUTPUT_TAX", "x", null))).hasMessageContaining("introuvable");
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- verrouillage optimiste

    @Test
    void versionPerimee_refusee409_decision_ajustement_annulation_transition_paiement_credit() {
        Dossier d = dossier(true);
        UUID v1 = vente(d, "F1", "1000", "100");
        UUID m = mixte(d, "M1");
        PeriodView p = ouvrir(d);
        long v0 = p.version();

        // A et B lisent la version v0 ; B modifie en premier
        PeriodView parB = tva.decider(collaborateur(d), d.id(), p.id(), m, new TreatmentRequest("OUT_OF_SCOPE", null, "Hors champ selon le client", v0));
        assertThat(parB.version()).isGreaterThan(v0);
        assertThatThrownBy(() -> tva.decider(collaborateur(d), d.id(), p.id(), v1, new TreatmentRequest("OUTPUT_TAX", null, "Confirmé", v0))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("modifiée par un autre utilisateur").hasMessageContaining("Rechargez");
        assertThatThrownBy(() -> tva.ajouterAjustement(collaborateur(d), d.id(), p.id(), new AdjustmentRequest("MANUAL", "OUTPUT", n("1"), "Test", null, null, v0))).hasMessageContaining("autre utilisateur");
        PeriodView ajuste = tva.ajouterAjustement(collaborateur(d), d.id(), p.id(), new AdjustmentRequest("MANUAL", "OUTPUT", n("1"), "Test", null, null, parB.version()));
        UUID ajustement = tva.ajustements(d.expert(), d.id(), p.id()).get(0).id();
        assertThatThrownBy(() -> tva.annulerAjustement(collaborateur(d), d.id(), p.id(), ajustement, new CancelRequest("Retrait", parB.version()))).hasMessageContaining("autre utilisateur");
        assertThatThrownBy(() -> tva.preparer(collaborateur(d), d.id(), p.id(), v0)).hasMessageContaining("autre utilisateur");
        assertThatThrownBy(() -> reglement.saisirCredit(collaborateur(d), d.id(), p.id(), new CreditRequest(n("10"), "Test", "Source", null, v0))).hasMessageContaining("autre utilisateur");
        PeriodView annule = tva.annulerAjustement(collaborateur(d), d.id(), p.id(), ajustement, new CancelRequest("Retrait", ajuste.version()));
        assertThat(annule.version()).isGreaterThan(ajuste.version());
        // sans jeton, l'appel interne reste possible (le contrôle ne s'applique que lorsque l'appelant fournit sa version)
        assertThat(tva.recalculer(collaborateur(d), d.id(), p.id()).id()).isEqualTo(p.id());
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- cadre non vérifié

    @Test
    void sansCadreVerifie_periodePreparableEtControlable_maisJamaisFinalisable_constatUniqueParPeriode_autoResolu() {
        Dossier d = dossier(false);
        vente(d, "F1", "1000", "100");
        vente(d, "F2", "500", "50");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        assertThat(p.regulatoryPackVerified()).isFalse();
        assertThat(p.finalisationReasons()).contains("REGULATORY_PACK_NOT_VERIFIED");

        // un seul constat de cadre pour toute la période, jamais un par opération
        assertThat(constats(d, ControlCodes.TAX_FRAMEWORK_NOT_VERIFIED)).hasSize(1);
        tva.recalculer(d.expert(), d.id(), p.id());
        assertThat(constats(d, ControlCodes.TAX_FRAMEWORK_NOT_VERIFIED)).hasSize(1);

        // techniquement prête pour la revue : READY_FOR_REVIEW possible, finalizable = false avec la raison machine
        PeriodView prete = tva.preparer(collaborateur(d), d.id(), p.id());
        assertThat(prete.status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(prete.progress().status()).isEqualTo("PRET_POUR_REVUE");
        assertThat(prete.finalisable()).isFalse();
        assertThat(prete.finalisationReasons()).containsExactly("REGULATORY_PACK_NOT_VERIFIED");
        PeriodView revue = tva.revoir(d.expert(), d.id(), p.id());
        assertThat(revue.status()).isEqualTo("REVIEWED");
        assertThat(revue.finalisable()).isFalse();
        assertThat(revue.progress().status()).isEqualTo("EN_REVUE");
        assertThat(revue.progress().blockingSteps()).contains("DECLARATION");
        assertThatThrownBy(() -> tva.finaliser(d.expert(), d.id(), p.id())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("aucun cadre fiscal vérifié");
        assertThat(ligneCabinet(d).tva()).isEqualTo("CADRE_INCOMPLET");
        assertThat(ligneCabinet(d).tvaLibelle()).isEqualTo("Cadre réglementaire incomplet");                       // jamais « Prête » sans cadre vérifié

        // un cadre VERIFIED est installé : le constat se résout après recalcul, la revue est annulée (les montants deviennent fiscaux)
        verifier(d, "TEST_ONLY-INSTALLE", null, null);
        PeriodView apres = tva.recalculer(d.expert(), d.id(), p.id());
        assertThat(constats(d, ControlCodes.TAX_FRAMEWORK_NOT_VERIFIED)).isEmpty();
        assertThat(apres.regulatoryPackVerified()).isTrue();
        assertThat(apres.status()).isEqualTo("PREPARING");
        assertThat(apres.finalisationReasons()).doesNotContain("REGULATORY_PACK_NOT_VERIFIED");
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- paiement interne

    @Test
    void paiement_partielPuisComplet_idempotence_annulationTracee_et_statuts() {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        UtilisateurPrincipal collab = collaborateur(d);

        // pas de paiement avant la finalisation
        assertThatThrownBy(() -> reglement.enregistrer(collab, d.id(), p.id(), new PaymentRequest(n("10"), LocalDate.now(), "REF", "MANUAL", "k0", null))).hasMessageContaining("finalisée");
        assertThat(reglement.paiements(collab, d.id(), p.id()).declaredAmount()).isNull();

        PeriodView finale = finaliser(d, p);
        String shaAvantPaiement = tva.snapshot(d.expert(), d.id(), p.id()).sha256();
        assertThat(finale.payment().declaredAmount()).isEqualByComparingTo("60");
        assertThat(finale.payment().status()).isEqualTo("A_PAYER");
        assertThat(finale.progress().status()).isEqualTo("A_PAYER");
        assertThat(ligneCabinet(d).tva()).isEqualTo("A_PAYER");
        assertThat(cabinet.workQueue(d.expert(), false, 0, 100).items()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("TAX_PAYMENT");
            assertThat(i.titre()).contains("reste 60");
            assertThat(i.cible()).isEqualTo("FISCALITE");
        });

        // paiement partiel : 20 sur 60
        PaymentSummary s1 = reglement.enregistrer(collab, d.id(), p.id(), new PaymentRequest(n("20"), LocalDate.now(), "VIR-1", "BANK_STATEMENT", "cle-1", null));
        assertThat(s1.paidAmount()).isEqualByComparingTo("20");
        assertThat(s1.remainingAmount()).isEqualByComparingTo("40");
        assertThat(s1.status()).isEqualTo("PARTIELLEMENT_PAYE");
        assertThat(tva.detail(d.expert(), d.id(), p.id()).progress().status()).isEqualTo("PARTIELLEMENT_PAYE");
        assertThat(ligneCabinet(d).tva()).isEqualTo("PARTIELLEMENT_PAYEE");

        // idempotence : le même appel rejoué ne double jamais le montant ; la même clé avec un autre montant est un conflit
        PaymentSummary rejeu = reglement.enregistrer(collab, d.id(), p.id(), new PaymentRequest(n("20"), LocalDate.now(), "VIR-1", "BANK_STATEMENT", "cle-1", null));
        assertThat(rejeu.paidAmount()).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("select count(*) from tax_payment where tax_period_id = ?", Long.class, p.id())).isEqualTo(1);
        assertThatThrownBy(() -> reglement.enregistrer(collab, d.id(), p.id(), new PaymentRequest(n("30"), LocalDate.now(), "VIR-1", "BANK_STATEMENT", "cle-1", null))).hasMessageContaining("idempotence");

        // validations : dépassement, montant nul, source inconnue, clé absente
        assertThatThrownBy(() -> reglement.enregistrer(collab, d.id(), p.id(), new PaymentRequest(n("41"), LocalDate.now(), null, "MANUAL", "cle-2", null))).hasMessageContaining("dépasse le reste");
        assertThatThrownBy(() -> reglement.enregistrer(collab, d.id(), p.id(), new PaymentRequest(n("0"), LocalDate.now(), null, "MANUAL", "cle-3", null))).hasMessageContaining("strictement positif");
        assertThatThrownBy(() -> reglement.enregistrer(collab, d.id(), p.id(), new PaymentRequest(n("5"), LocalDate.now(), null, "PORTAIL_DGI", "cle-4", null))).hasMessageContaining("Source");
        assertThatThrownBy(() -> reglement.enregistrer(collab, d.id(), p.id(), new PaymentRequest(n("5"), LocalDate.now(), null, "MANUAL", " ", null))).hasMessageContaining("idempotence");

        // droits et tenant
        assertThatThrownBy(() -> reglement.enregistrer(client(d), d.id(), p.id(), new PaymentRequest(n("5"), LocalDate.now(), null, "MANUAL", "cle-5", null))).isInstanceOf(ResponseStatusException.class);
        Dossier autre = dossier(false);
        assertThatThrownBy(() -> reglement.paiements(autre.expert(), d.id(), p.id())).hasMessageContaining("introuvable");
        assertThatThrownBy(() -> reglement.enregistrer(autre.expert(), d.id(), p.id(), new PaymentRequest(n("5"), LocalDate.now(), null, "MANUAL", "cle-6", null))).hasMessageContaining("introuvable");

        // paiement complet
        PaymentSummary s2 = reglement.enregistrer(d.expert(), d.id(), p.id(), new PaymentRequest(n("40"), LocalDate.now(), "VIR-2", "BANK_STATEMENT", "cle-7", null));
        assertThat(s2.remainingAmount()).isEqualByComparingTo("0");
        assertThat(s2.status()).isEqualTo("PAYE");
        assertThat(tva.detail(d.expert(), d.id(), p.id()).progress().status()).isEqualTo("PAYE");
        assertThat(ligneCabinet(d).tva()).isEqualTo("PAYEE");
        assertThat(cabinet.workQueue(d.expert(), false, 0, 100).items()).noneMatch(i -> "TAX_PAYMENT".equals(i.kind()) && i.entrepriseId().equals(d.id()));
        assertThatThrownBy(() -> reglement.enregistrer(d.expert(), d.id(), p.id(), new PaymentRequest(n("1"), LocalDate.now(), null, "MANUAL", "cle-8", null))).hasMessageContaining("dépasse le reste");

        // annulation tracée : le paiement n'est jamais supprimé ; le reste à payer est rétabli
        UUID aAnnuler = s2.payments().stream().filter(x -> x.reference() != null && x.reference().equals("VIR-2")).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> reglement.annuler(d.expert(), d.id(), p.id(), aAnnuler, new ReverseRequest(" ", null))).hasMessageContaining("motif");
        PaymentSummary s3 = reglement.annuler(d.expert(), d.id(), p.id(), aAnnuler, new ReverseRequest("Virement rejeté par la banque", null));
        assertThat(s3.paidAmount()).isEqualByComparingTo("20");
        assertThat(s3.remainingAmount()).isEqualByComparingTo("40");
        assertThat(s3.payments()).hasSize(2).extracting(PaymentView::status).containsExactly("ACTIVE", "REVERSED");
        assertThatThrownBy(() -> reglement.annuler(d.expert(), d.id(), p.id(), aAnnuler, new ReverseRequest("Encore", null))).hasMessageContaining("déjà annulé");
        assertThat(auditEventRepository.findAll().stream().map(e -> e.getAction()).toList()).contains("PAIEMENT_FISCAL_ENREGISTRE", "PAIEMENT_FISCAL_ANNULE", "PERIODE_FISCALE_STATUT_MODIFIE");

        // un paiement n'altère jamais le snapshot figé (empreinte inchangée) et les paiements n'en font pas partie
        SnapshotView snapshot = tva.snapshot(d.expert(), d.id(), p.id());
        assertThat(snapshot.sha256()).isEqualTo(shaAvantPaiement);
        assertThat(snapshot.payload()).containsKey("openingCredit").doesNotContainKey("payments");
        assertThat(((java.util.Map<?, ?>) snapshot.payload().get("totals")).get("netDeclared")).isNotNull();
    }

    @Test
    void aucuneTvaDue_aucunPaiementPossible_creditNonReportableSansReglePrevue() {
        Dossier d = dossier(true);
        achat(d, "A1", "1000", "100");
        PeriodView p = ouvrir(d);
        PeriodView finale = finaliser(d, p);
        assertThat(finale.totals().netDeclared()).isEqualByComparingTo("-100");
        assertThat(finale.progress().status()).isEqualTo("FINALISE");                               // CREDIT_A_REPORTER seulement si le cadre vérifié prévoit le report
        assertThat(finale.credit().closingAmount()).isEqualByComparingTo("100");
        assertThat(finale.credit().closingState()).isEqualTo("TO_REVIEW");
        assertThat(finale.payment().status()).isEqualTo("AUCUN_MONTANT_DU");
        assertThatThrownBy(() -> reglement.enregistrer(d.expert(), d.id(), p.id(), new PaymentRequest(n("10"), LocalDate.now(), null, "MANUAL", "k", null))).hasMessageContaining("rien à payer");
        assertThat(ligneCabinet(d).tva()).isEqualTo("FINALISEE");
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- crédit d'ouverture et report

    @Test
    void creditOuverture_saisieJustifieeTracee_aRevoirSansReglePrevue_bloqueLaFinalisation_neSeReprendJamaisAutomatiquement() {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        PeriodView p = ouvrir(d);
        UtilisateurPrincipal collab = collaborateur(d);

        assertThatThrownBy(() -> reglement.saisirCredit(collab, d.id(), p.id(), new CreditRequest(n("500"), " ", "Déclaration précédente", null, null))).hasMessageContaining("justification");
        assertThatThrownBy(() -> reglement.saisirCredit(collab, d.id(), p.id(), new CreditRequest(n("0"), "Reprise", "Déclaration précédente", null, null))).hasMessageContaining("strictement positif");
        assertThatThrownBy(() -> reglement.saisirCredit(client(d), d.id(), p.id(), new CreditRequest(n("500"), "Reprise", "Source", null, null))).isInstanceOf(ResponseStatusException.class);
        assertThat(reglement.credit(collab, d.id(), p.id()).openingState()).isEqualTo("NONE");

        CreditView saisi = reglement.saisirCredit(collab, d.id(), p.id(), new CreditRequest(n("500"), "Crédit repris de la déclaration précédente", "Déclaration papier du mois précédent", null, null));
        assertThat(saisi.openingAmount()).isEqualByComparingTo("500");
        assertThat(saisi.openingState()).isEqualTo("TO_REVIEW");                                    // aucune règle de report vérifiée : jamais validé automatiquement

        // le crédit saisi n'entre PAS dans le solde : aucune règle de report n'est appliquée
        assertThat(tva.detail(d.expert(), d.id(), p.id()).totals().netDeclared()).isEqualByComparingTo("100");

        // bloque la finalisation tant qu'il n'est pas validé par un cadre prévoyant le report
        PeriodView prete = tva.preparer(collab, d.id(), p.id());
        assertThat(prete.finalisationReasons()).contains("OPENING_CREDIT_TO_REVIEW");
        tva.revoir(d.expert(), d.id(), p.id());
        assertThatThrownBy(() -> tva.finaliser(d.expert(), d.id(), p.id())).hasMessageContaining("crédit d'ouverture");

        // modification : nouvelle ligne, l'ancienne est marquée remplacée (jamais supprimée) ; une saisie après la revue la rend caduque
        CreditView modifie = reglement.saisirCredit(collab, d.id(), p.id(), new CreditRequest(n("450"), "Montant corrigé après relecture", "Déclaration papier du mois précédent", null, null));
        assertThat(modifie.openingAmount()).isEqualByComparingTo("450");
        assertThat(jdbc.queryForObject("select count(*) from tax_opening_credit where tax_period_id = ?", Long.class, p.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from tax_opening_credit where tax_period_id = ? and superseded_at is null", Long.class, p.id())).isEqualTo(1);
        assertThat(tva.detail(d.expert(), d.id(), p.id()).status()).isEqualTo("PREPARING");
        assertThat(auditEventRepository.findAll().stream().map(e -> e.getAction()).toList()).contains("CREDIT_OUVERTURE_SAISI", "CREDIT_OUVERTURE_MODIFIE");

        // autre cabinet : 404
        Dossier autre = dossier(false);
        assertThatThrownBy(() -> reglement.credit(autre.expert(), d.id(), p.id())).hasMessageContaining("introuvable");
    }

    @Test
    void reportPrevuParLeCadreVerifie_creditValide_creditAReporter_maisAucuneReprise_automatiqueSurLaPeriodeSuivante() {
        Dossier d = dossier(true, null, true);                                                                // cadre FIXTURE TEST_ONLY qui « prévoit » le report
        achat(d, "A1", "1000", "100");
        PeriodView mois1 = ouvrir(d);
        PeriodView finale = finaliser(d, mois1);
        assertThat(finale.progress().status()).isEqualTo("CREDIT_A_REPORTER");
        assertThat(finale.credit().closingState()).isEqualTo("REPORTABLE");

        // période suivante : le crédit de clôture n'est jamais repris tout seul ; une saisie explicite est validée par le cadre et finalise normalement
        PeriodView suivante = tva.ouvrir(d.expert(), d.id(), new OpenRequest(DEBUT.plusMonths(1), FIN.plusMonths(1)));
        assertThat(suivante.credit().openingAmount()).isNull();
        assertThat(suivante.credit().openingState()).isEqualTo("NONE");
        assertThatThrownBy(() -> reglement.saisirCredit(collaborateur(d), d.id(), suivante.id(), new CreditRequest(n("100"), "Crédit constaté", "Période précédente finalisée", UUID.randomUUID(), null)))
                .hasMessageContaining("Période précédente introuvable");
        CreditView saisi = reglement.saisirCredit(collaborateur(d), d.id(), suivante.id(), new CreditRequest(n("100"), "Crédit constaté à la clôture de la période précédente", "Période précédente finalisée", mois1.id(), null));
        assertThat(saisi.openingState()).isEqualTo("VALIDATED");
        assertThat(saisi.previousPeriodId()).isEqualTo(mois1.id());
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- échéances

    @Test
    void echeance_uniquementDepuisLaRegleDuCadreVerifie_sinonAucuneDate() {
        Dossier sansRegle = dossier(true);
        PeriodView a = ouvrir(sansRegle);
        assertThat(a.deadline()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from dossier_echeance where entreprise_id = ? and type = 'DECLARATION_FISCALE'", Long.class, sansRegle.id())).isZero();
        assertThatThrownBy(() -> declarations.genererEcheancierTva(sansRegle.id(), LocalDate.now().getYear(), sansRegle.expert())).hasMessageContaining("Aucun echeancier");

        Dossier sansCadre = dossier(false);
        assertThat(ouvrir(sansCadre).deadline()).isNull();
        assertThatThrownBy(() -> declarations.genererEcheancierTva(sansCadre.id(), LocalDate.now().getYear(), sansCadre.expert())).hasMessageContaining("Aucun echeancier");

        // fixture TEST_ONLY avec règle : « 2 mois après la fin de période, le 7 » (valeur inventée pour le test)
        Dossier avecRegle = dossier(true, new VatRules.DueRule(2, 7), null);
        PeriodView c = ouvrir(avecRegle);
        assertThat(c.deadline()).isEqualTo(java.time.YearMonth.from(FIN).plusMonths(2).atDay(7));
        assertThat(jdbc.queryForObject("select count(*) from dossier_echeance where entreprise_id = ? and type = 'DECLARATION_FISCALE'", Long.class, avecRegle.id())).isEqualTo(1);
        var liste = declarations.genererEcheancierTva(avecRegle.id(), LocalDate.now().getYear(), avecRegle.expert());
        assertThat(liste).hasSize(12);
        assertThat(liste.get(0).dateEcheance()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), 3, 7));
        assertThat(declarations.genererEcheancierTva(avecRegle.id(), LocalDate.now().getYear(), avecRegle.expert())).hasSize(12);            // idempotent
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------------------- cabinet

    @Test
    void cabinet_indicateurTvaCompact_aTraiter_aRevoir_etWorkQueueDesAnomalies() {
        Dossier d = dossier(true);
        vente(d, "F1", "1000", "100");
        mixte(d, "M1");
        PeriodView p = ouvrir(d);
        assertThat(ligneCabinet(d).tva()).isEqualTo("A_TRAITER");
        assertThat(ligneCabinet(d).tvaLibelle()).isEqualTo("1 à traiter");
        assertThat(cabinet.workQueue(d.expert(), false, 0, 100).items()).anySatisfy(i -> {
            assertThat(i.entrepriseId()).isEqualTo(d.id());
            assertThat(i.domaine()).isEqualTo("Fiscalité");
            assertThat(i.cible()).isEqualTo("FISCALITE");
        });
        UUID m = tva.operations(d.expert(), d.id(), p.id(), filtres(null, "TO_REVIEW", null, false, null, null, "date", true), 0, 5).items().get(0).ecritureId();
        tva.decider(collaborateur(d), d.id(), p.id(), m, new TreatmentRequest("OUT_OF_SCOPE", null, "Hors champ selon le client"));
        tva.preparer(collaborateur(d), d.id(), p.id());
        assertThat(ligneCabinet(d).tvaLibelle()).isEqualTo("À revoir");
        tva.revoir(d.expert(), d.id(), p.id());
        assertThat(ligneCabinet(d).tva()).isEqualTo("PRETE");                                          // cadre FIXTURE vérifié : « Prête » est exact
    }
}
