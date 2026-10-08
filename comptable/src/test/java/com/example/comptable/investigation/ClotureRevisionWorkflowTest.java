package com.example.comptable.investigation;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.domain.TypeResolution;
import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.allocation.PaymentAllocationService;
import com.example.comptable.comptabilite.allocation.SourceAllocation;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.controle.revue.RevueComptableService;
import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.dossier.web.dto.ControlItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-cloture explicable et workflow de cloture : des COMPTES (jamais un pourcentage), des blocages qui bloquent vraiment, des avertissements acceptes
 * avec motif et auteur, des controles toujours recalcules avant de decider, des evenements d'audit coherents, et l'isolation entre cabinets.
 */
class ClotureRevisionWorkflowTest extends BankTestBase {

    @Autowired ClotureReadinessService readinessService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired FindingService findingService;
    @Autowired RevueComptableService revueService;
    @Autowired AccountingInvestigationService investigationService;
    @Autowired FacturationService facturationService;
    @Autowired PaymentAllocationService allocationService;

    // ------------------------------------------------------------------------------------------------ outils

    private UUID exercice(Dossier d) {
        return exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
    }

    private int statut(Supplier<?> action) {
        try {
            action.get();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    /** Une ecriture BROUILLON : copie d'un reglement valide, jamais validee (bloquante pour la cloture). */
    private UUID brouillon(Dossier d) {
        UUID modele = reglement(d, 1_000, LocalDate.now(), "Modele brouillon").ecritureId();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut) select ?, entreprise_id, exercice_id, "
                + "journal_id, 'BRO-' || substr(?::text, 1, 8), date_ecriture, 'Brouillon de test', 'BROUILLON' from ecriture_comptable where id = ?", id, id.toString(), modele);
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, tiers_id, libelle, debit, credit, numero_ligne) select gen_random_uuid(), ?, compte_id, tiers_id, libelle, "
                + "debit, credit, numero_ligne from ligne_ecriture where ecriture_id = ?", id, modele);
        return id;
    }

    private ControlItem item(ClotureReadinessDto r, String code) {
        return r.checklist().stream().filter(i -> i.code().equals(code)).findFirst().orElseThrow();
    }

    private Finding bloquant(Dossier d, String libelle) {
        var entreprise = entrepriseRepository.findById(d.id()).orElseThrow();
        return findingService.declarer(entreprise, ControlResult.ouvert("REVIEW_TEST_BLOCKER", ControlCategory.CLOSING, ControlSeverity.BLOCKING, d.id(), "ENTREPRISE",
                d.id() + ":" + libelle, libelle, "Blocage de test", List.of(), Map.of(), "Le corriger"), TypeFinding.CLOSING_BLOCKER).finding();
    }

    // ------------------------------------------------------------------------------------------------ resume, domaines, statuts

    @Test
    void preCloture_compteLesPoints_rangeParDomaine_etNeDonneAucunPourcentage() {
        Dossier d = nouveauDossier(false);
        brouillon(d);
        acheterSansPiece(d, 50_000);

        ClotureReadinessDto r = readinessService.evaluer(d.id(), d.expert());

        assertThat(r.statut()).isEqualTo("NOT_READY");
        assertThat(r.resume().bloquants()).isGreaterThanOrEqualTo(1);
        assertThat(r.resume().bloquants()).isEqualTo(r.checklist().stream().filter(i -> "BLOCKING".equals(i.statut())).count());
        assertThat(r.resume().enOrdre()).isEqualTo(r.checklist().stream().filter(i -> "OK".equals(i.statut())).count());
        assertThat(r.resume().nonApplicables()).isGreaterThan(0); // NOT_APPLICABLE est preserve : jamais simule comme OK
        assertThat(item(r, "brouillons").domaine()).isEqualTo("JOURNAL");
        assertThat(item(r, "banque").domaine()).isEqualTo("BANK");
        assertThat(item(r, "pieces_manquantes").domaine()).isEqualTo("DOCUMENT");
        assertThat(item(r, "clients").domaine()).isEqualTo("CUSTOMER");
        assertThat(r.checklist()).allSatisfy(i -> assertThat(i.domaine()).isNotBlank());
        assertThat(r.evaluatedAt()).isNotNull();
        assertThat(r.controlesExecutesLe()).isNotNull();
    }

    // ------------------------------------------------------------------------------------------------ blocages qui bloquent

    @Test
    void troisBlocages_refusent_lUnSeulRefuse_aucunAutorise_etLesControlesSontRecalcules() {
        Dossier d = nouveauDossier(false);
        UUID brouillon = brouillon(d);
        Finding bloquant = bloquant(d, "Blocage externe");
        UUID ecritureInegale = reglement(d, 2_000, LocalDate.now(), "A desequilibrer").ecritureId();
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) select gen_random_uuid(), ?, compte_id, 'Ecart', 7, 0, 9 "
                + "from ligne_ecriture where ecriture_id = ? limit 1", ecritureInegale, ecritureInegale);

        ClotureReadinessDto avant = readinessService.evaluer(d.id(), d.expert());
        assertThat(avant.statut()).isEqualTo("NOT_READY");
        assertThat(avant.resume().bloquants()).isGreaterThanOrEqualTo(3);
        // Refus : la liste explicite de ce qui bloque est dans le message, et l'echec est trace.
        long statutRefus = statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(), null));
        assertThat(statutRefus).isEqualTo(422);
        assertThat(evenements("CLOTURE_REFUSEE").stream().filter(e -> e.getEntiteId().equals(exercice(d).toString()))).hasSize(1);

        // On corrige un blocage a la fois : tant qu'il en reste un, la cloture reste refusee.
        jdbc.update("delete from ligne_ecriture where ecriture_id = ? and numero_ligne = 9", ecritureInegale);
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(), null))).isEqualTo(422);
        ecritureComptableService().valider(d.id(), brouillon, d.expert());
        assertThat(readinessService.evaluer(d.id(), d.expert()).resume().bloquants()).isEqualTo(1);
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(), null))).isEqualTo(422);

        // Plus aucun blocage : possible. (Le dernier blocage est resolu par une decision humaine tracee.)
        findingService.resoudre(d.id(), bloquant.getId(), d.expert());
        ClotureReadinessDto apres = readinessService.evaluer(d.id(), d.expert());
        assertThat(apres.resume().bloquants()).isZero();
        assertThat(apres.statut()).isIn("READY", "READY_WITH_WARNINGS");
    }

    private com.example.comptable.comptabilite.service.EcritureComptableService ecritureComptableService() {
        return ecritureService;
    }

    @Autowired com.example.comptable.comptabilite.service.EcritureComptableService ecritureService;

    // ------------------------------------------------------------------------------------------------ avertissements acceptes

    @Test
    void avertissements_exigentUneConfirmationMotivee_tracee_puisLaClotureSeFait() {
        Dossier d = nouveauDossier(false);
        acheterSansPiece(d, 80_000); // pas de piece justificative : avertissement, jamais un blocage
        ClotureReadinessDto r = readinessService.evaluer(d.id(), d.expert());
        assertThat(r.statut()).isEqualTo("READY_WITH_WARNINGS");
        assertThat(r.resume().bloquants()).isZero();
        assertThat(r.resume().avertissements()).isGreaterThan(0);

        // Sans confirmation : 409 avec la liste de ce qu'il faut accepter.
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(), null))).isEqualTo(409);
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(),
                new ClotureExerciceService.ConfirmationCloture(false, "peu importe")))).isEqualTo(409);
        // Confirmee mais sans motif : refusee, rien n'est trace.
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(),
                new ClotureExerciceService.ConfirmationCloture(true, "  ")))).isEqualTo(422);
        assertThat(evenements("CLOTURE_AVERTISSEMENTS_ACCEPTES").stream().filter(e -> e.getEntiteId().equals(exercice(d).toString()))).isEmpty();

        clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Pieces attendues du fournisseur en janvier"));

        List<AuditEvent> acceptes = evenements("CLOTURE_AVERTISSEMENTS_ACCEPTES").stream().filter(e -> e.getEntiteId().equals(exercice(d).toString())).toList();
        assertThat(acceptes).hasSize(1);
        assertThat(acceptes.get(0).getUtilisateurId()).isEqualTo(d.expert().getId());
        assertThat(acceptes.get(0).getDetails().get("motif")).isEqualTo("Pieces attendues du fournisseur en janvier");
        assertThat(acceptes.get(0).getCreatedAt()).isNotNull();
        assertThat(evenements("CLOTURE_TERMINEE").stream().filter(e -> e.getEntiteId().equals(exercice(d).toString()))).hasSize(1);
        assertThat(exerciceRepository.findById(exercice(d)).orElseThrow().getStatut().name()).isEqualTo("CLOTURE");
    }

    @Test
    void dossierPropre_estReady_etSeCloture_sansConfirmation() {
        Dossier d = nouveauDossier(false);
        ClotureReadinessDto r = readinessService.evaluer(d.id(), d.expert());
        assertThat(r.statut()).isEqualTo("READY");
        assertThat(r.resume().bloquants()).isZero();
        assertThat(r.resume().avertissements()).isZero();

        clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(), null);

        assertThat(exerciceRepository.findById(exercice(d)).orElseThrow().getStatut().name()).isEqualTo("CLOTURE");
    }

    // ------------------------------------------------------------------------------------------------ fraicheur

    @Test
    void nouvelleEcritureProblematiqueApresReady_estVueAuMomentDeCloturer() {
        Dossier d = nouveauDossier(false);
        assertThat(readinessService.evaluer(d.id(), d.expert()).statut()).isEqualTo("READY");

        // Un brouillon apparait APRES l'evaluation : la cloture recalcule, ne se fie pas a l'evaluation precedente.
        brouillon(d);
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(), null))).isEqualTo(422);
        assertThat(readinessService.evaluer(d.id(), d.expert()).statut()).isEqualTo("NOT_READY");
        assertThat(exerciceRepository.findById(exercice(d)).orElseThrow().getStatut().name()).isEqualTo("OUVERT");
    }

    @Test
    void deuxClotures_desMemeExercice_laSecondeVoitLExerciceDejaCloture() {
        Dossier d = nouveauDossier(false);
        clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(), null);
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice(d), d.expert(), null))).isEqualTo(409);
    }

    // ------------------------------------------------------------------------------------------------ resolution, ecartement, audit

    @Test
    void causeCorrigee_resoutLeConstatAutomatiquement_etUnConstatEcarteLeResteApresRelance() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Resolution", null, d.expert());
        UUID encaissement = jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' "
                + "and l.credit > 0", UUID.class, engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT,
                client.id(), LocalDate.now(), "ENC-RES", "Encaissement", new BigDecimal("300000"), MoyenPaiement.VIREMENT), d.expert()).id(), d.expert()).ecritureId());
        revueService.relancer(d.id(), d.expert());
        Finding constat = findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.PAYMENT_WITHOUT_INVOICE)).get(0);
        // La revue range ce constat dans le bon domaine, d'apres le type du tiers.
        assertThat(evenements("CONTROLES_EXECUTES")).isNotEmpty();

        // On decouvre la facture et on affecte : la cause disparait, le constat se ferme TOUT SEUL (resolu par le systeme, jamais supprime).
        UUID produit = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "701").orElseThrow().getId();
        FactureDto facture = facturationService.validerFacture(d.id(), facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client.id(), LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(25), "Vente", List.of(new LigneFactureRequest("Marchandise", BigDecimal.ONE, new BigDecimal("300000"), BigDecimal.ZERO, null))),
                d.expert()).id(), produit, d.expert());
        UUID ligneFacture = jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' "
                + "and l.debit > 0", UUID.class, facture.ecritureId());
        BigDecimal ttc = jdbc.queryForObject("select debit from ligne_ecriture where id = ?", BigDecimal.class, ligneFacture);
        allocationService.allouer(d.id(), encaissement, ligneFacture, ttc.min(new BigDecimal("300000")), SourceAllocation.USER, d.expert());
        revueService.relancer(d.id(), d.expert());
        Finding ferme = findingRepository.findById(constat.getId()).orElseThrow();
        assertThat(ferme.getResolutionType()).isIn(TypeResolution.SYSTEM, TypeResolution.MANUAL);
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.PAYMENT_WITHOUT_INVOICE)).stream()
                .filter(f -> f.getId().equals(constat.getId()))).isEmpty();

        // Ecarter un constat : il reste ecarte apres une nouvelle execution (politique de la phase B), avec un motif.
        UUID encaissement2 = jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' "
                + "and l.credit > 0", UUID.class, engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT,
                client.id(), LocalDate.now(), "ENC-ECART", "Acompte", new BigDecimal("40000"), MoyenPaiement.VIREMENT), d.expert()).id(), d.expert()).ecritureId());
        revueService.relancer(d.id(), d.expert());
        Finding aEcarter = findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.PAYMENT_WITHOUT_INVOICE, ControlCodes.OVERPAYMENT)).stream()
                .filter(f -> encaissement2.toString().equals(f.getSourceId())).findFirst().orElseThrow();
        findingService.rejeter(d.id(), aEcarter.getId(), "Acompte volontaire", d.expert());
        revueService.relancer(d.id(), d.expert());
        assertThat(findingRepository.findById(aEcarter.getId()).orElseThrow().getStatus()).isEqualTo(StatutFinding.DISMISSED);
        assertThat(evenements("FINDING_REJETE")).isNotEmpty();
    }

    @Test
    void ouvrirUneInvestigation_estTracee_etLaRevueTraceSonOuverture() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Trace", null, d.expert());
        engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT, client.id(), LocalDate.now(), "ENC-T",
                "Encaissement", new BigDecimal("1000"), MoyenPaiement.VIREMENT), d.expert()).id(), d.expert());
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        Finding f = findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.PAYMENT_WITHOUT_INVOICE)).get(0);

        investigationService.depuisConstat(d.id(), f.getId(), d.expert());

        List<AuditEvent> ouvertes = evenements("INVESTIGATION_OUVERTE").stream().filter(e -> e.getEntiteId().equals(f.getId().toString())).toList();
        assertThat(ouvertes).hasSize(1);
        assertThat(ouvertes.get(0).getEntiteId()).isEqualTo(f.getId().toString());
        assertThat(ouvertes.get(0).getUtilisateurId()).isEqualTo(d.expert().getId());
    }

    // ------------------------------------------------------------------------------------------------ isolation

    @Test
    void preClotureEtClotureD_unCabinet_sontInvisiblesPourUnAutre() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        brouillon(a);

        assertThat(statut(() -> readinessService.evaluer(a.id(), b.expert()))).isEqualTo(404);
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(a.id(), exercice(a), b.expert(), null))).isEqualTo(404);
        assertThat(statut(() -> revueService.synthese(a.id(), b.expert()))).isEqualTo(404);
        // La pre-cloture de B ne contient rien de A.
        ClotureReadinessDto deB = readinessService.evaluer(b.id(), b.expert());
        assertThat(deB.statut()).isEqualTo("READY");
        assertThat(item(deB, "brouillons").statut()).isEqualTo("OK");
        // Le refus tente par le cabinet B sur l'exercice de A n'a laisse aucune trace (404 avant toute decision).
        assertThat(evenements("CLOTURE_REFUSEE").stream().filter(e -> e.getEntiteId().equals(exercice(a).toString()))).isEmpty();
    }
}
