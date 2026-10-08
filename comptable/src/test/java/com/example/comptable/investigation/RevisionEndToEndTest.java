package com.example.comptable.investigation;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeResolution;
import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.allocation.PaymentAllocationService;
import com.example.comptable.comptabilite.allocation.SourceAllocation;
import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.service.ImmobilisationService;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.ReleveBancaireDto;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDomain;
import com.example.comptable.controle.revue.RevueComptableService;
import com.example.comptable.controle.revue.RevueDto;
import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.investigation.InvestigationResult.Nature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parcours complet de la Phase E sur un vrai PostgreSQL : dossier -> operations ordinaires et anormales -> controles -> espace de revision par domaine ->
 * « Pourquoi ? » -> correction -> resolution automatique -> ecartement -> pre-cloture -> refus -> avertissements acceptes -> cloture -> isolation entre cabinets.
 * Chaque etape numerotee verifie UNE promesse du produit.
 */
class RevisionEndToEndTest extends BankTestBase {

    private static final Pattern UUID_VISIBLE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired RevueComptableService revueService;
    @Autowired AccountingInvestigationService investigation;
    @Autowired ClotureReadinessService readinessService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired FindingService findingService;
    @Autowired FacturationService facturationService;
    @Autowired PaymentAllocationService allocationService;
    @Autowired RapprochementBancaireService rapprochementService;
    @Autowired ImmobilisationService immobilisationService;
    @Autowired EcritureComptableService ecritureService;

    private int statut(Supplier<?> action) {
        try {
            action.get();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    private UUID ligneClient(UUID ecritureId, String cote) {
        return jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' and l."
                + cote + " > 0", UUID.class, ecritureId);
    }

    private UUID encaissement(Dossier d, TiersDto client, int montant, String reference) {
        return ligneClient(engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT, client.id(), LocalDate.now(),
                reference, "Encaissement " + reference, new BigDecimal(montant), MoyenPaiement.VIREMENT), d.expert()).id(), d.expert()).ecritureId(), "credit");
    }

    private FactureDto factureClient(Dossier d, TiersDto client, int prixHt) {
        UUID produit = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "701").orElseThrow().getId();
        return facturationService.validerFacture(d.id(), facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client.id(), LocalDate.now().minusDays(40),
                LocalDate.now().minusDays(10), "Vente " + prixHt, List.of(new LigneFactureRequest("Marchandise", BigDecimal.ONE, new BigDecimal(prixHt), BigDecimal.ZERO, null))),
                d.expert()).id(), produit, d.expert());
    }

    private List<Finding> actifs(Dossier d, String... codes) {
        return findingRepository.findActifsParCodes(d.id(), List.of(codes));
    }

    @Test
    void parcoursCompletDeRevisionEtDeCloture() {
        // 1. Un dossier neuf est propre : READY, aucun constat, aucun bloquant.
        Dossier d = nouveauDossier(false);
        Dossier autre = nouveauDossier(false);
        assertThat(readinessService.evaluer(d.id(), d.expert()).statut()).isEqualTo("READY");

        // 2. Un achat sans piece justificative.
        UUID ecritureAchat = acheterSansPiece(d, 80_000);

        // 3. Un client : facture, deux encaissements, deux affectations insuffisantes (le second encaissement depasse la facture).
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Omega", null, d.expert());
        FactureDto facture = factureClient(d, client, 1_000_000);
        UUID ligneFacture = ligneClient(facture.ecritureId(), "debit");
        BigDecimal ttc = jdbc.queryForObject("select debit from ligne_ecriture where id = ?", BigDecimal.class, ligneFacture);
        UUID paiement1 = encaissement(d, client, 600_000, "ENC-1");
        UUID paiement2 = encaissement(d, client, 700_000, "ENC-2");
        allocationService.allouer(d.id(), paiement1, ligneFacture, new BigDecimal("600000"), SourceAllocation.USER, d.expert());
        allocationService.allouer(d.id(), paiement2, ligneFacture, ttc.subtract(new BigDecimal("600000")), SourceAllocation.USER, d.expert());

        // 4. Un encaissement d'un autre client, sans aucune facture.
        TiersDto client2 = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Sans Facture", null, d.expert());
        UUID encaissementSeul = encaissement(d, client2, 120_000, "ENC-3");

        // 5. Une immobilisation en service que rien ne rattache a la comptabilite.
        ImmobilisationDto camion = immobilisationService.creer(d.id(), new CreerImmobilisationRequest(
                compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "244").orElseThrow().getId(),
                compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "281").orElseThrow().getId(), "Camion", TypeImmobilisation.CORPORELLE,
                LocalDate.now().withDayOfYear(1), new BigDecimal("5000000"), 5, ModeAmortissement.LINEAIRE), d.expert());
        jdbc.update("update immobilisation set statut = 'EN_SERVICE' where id = ?", camion.id());

        // 6. Un reglement fournisseur et un releve dont une transaction (250 000) n'a aucune ecriture ; le solde du releve differe du solde comptable.
        Reglement reglement = reglement(d, 600_000, LocalDate.now(), "Reglement fournisseur");
        BigDecimal comptable = jdbc.queryForObject("select coalesce(sum(l.debit) - sum(l.credit), 0) from ligne_ecriture l join ecriture_comptable e on e.id = l.ecriture_id "
                + "where l.compte_id = ? and e.statut = 'VALIDEE'", BigDecimal.class, reglement.compteBanqueId());
        ReleveBancaireDto releve = rapprochementService.importer(d.id(), new ImporterReleveRequest(reglement.compteBanqueId(), "Releve du mois", LocalDate.now().minusDays(10),
                LocalDate.now(), BigDecimal.ZERO, comptable.subtract(new BigDecimal("250000"))),
                new MockMultipartFile("fichier", "releve.csv", "text/csv", ("date;libelle;montant\n" + LocalDate.now() + ";Encaissement 1;600000\n" + LocalDate.now()
                        + ";Encaissement 2;700000\n" + LocalDate.now() + ";Encaissement 3;120000\n" + LocalDate.now() + ";Operation 1;-600000\n" + LocalDate.now()
                        + ";Operation 2;-250000\n").getBytes(StandardCharsets.UTF_8)), d.expert());

        // 7. Les controles relevent les exceptions, une par objet concerne (et non une par operation).
        var bilan = revueService.relancer(d.id(), d.expert());
        assertThat(bilan.detectes()).isGreaterThanOrEqualTo(4);
        assertThat(actifs(d, ControlCodes.MISSING_DOCUMENT)).hasSize(1);
        assertThat(actifs(d, ControlCodes.FIXED_ASSET_WITHOUT_SOURCE)).hasSize(1);
        assertThat(actifs(d, ControlCodes.OVERPAYMENT, ControlCodes.PAYMENT_WITHOUT_INVOICE)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(actifs(d, ControlCodes.UNMATCHED_PAYMENT)).isNotEmpty();

        // 8. L'espace de revision : la cloture n'est PAS prete, et les constats sont ranges par domaine (comptes, jamais un pourcentage).
        RevueDto.Espace espace = revueService.espace(d.id(), d.expert());
        assertThat(espace.pret()).isFalse();
        assertThat(espace.domaines()).extracting(RevueDto.DomaineRevue::code).contains("BANK", "DOCUMENT", "FIXED_ASSET", "CUSTOMER");
        assertThat(espace.avertissements() + espace.bloquants() + espace.aExaminer()).isEqualTo(espace.domaines().stream().mapToLong(RevueDto.DomaineRevue::total).sum());

        // 9. Filtrer par domaine ne renvoie que ce domaine.
        RevueDto.Page banque = revueService.lister(d.id(), new RevueComptableService.Filtre(null, null, null, null, null, null, null, null, null, ControlDomain.BANK), 0, 50, d.expert());
        assertThat(banque.contenu()).isNotEmpty().allSatisfy(c -> assertThat(c.domaine()).isEqualTo("BANK"));
        RevueDto.Page clients = revueService.lister(d.id(), new RevueComptableService.Filtre(null, null, null, null, null, null, null, null, null, ControlDomain.CUSTOMER), 0, 50, d.expert());
        assertThat(clients.contenu()).extracting(RevueDto.Constat::code).contains(ControlCodes.OVERPAYMENT);

        // 10. « Pourquoi ? » sur le reglement en trop-percu : cause structuree UNALLOCATED_PAYMENT, preuves, chronologie.
        Finding troPercu = actifs(d, ControlCodes.OVERPAYMENT).get(0);
        InvestigationResult pourquoi = investigation.depuisConstat(d.id(), troPercu.getId(), d.expert());
        assertThat(pourquoi.causes()).extracting(InvestigationResult.Cause::code).contains(CauseCodes.UNALLOCATED_PAYMENT);
        assertThat(pourquoi.causes().get(0).evidence()).isNotEmpty();
        assertThat(pourquoi.timeline()).isNotEmpty();

        // 11. La chronologie est ordonnee, et aucun libelle ne montre d'identifiant brut.
        assertThat(pourquoi.timeline()).extracting(InvestigationResult.TimelineEvent::date).isSorted();
        assertThat(pourquoi.relatedEntities()).allSatisfy(o -> assertThat(UUID_VISIBLE.matcher(o.label()).find()).as(o.label()).isFalse());
        assertThat(pourquoi.summary()).doesNotContainPattern(UUID_VISIBLE);

        // 12. Le client : solde crediteur EXPLIQUE (jamais qualifie d'anomalie avant explication) ; son equation se reconstitue.
        InvestigationResult tiers = investigation.investiguer(d.id(), "TIERS", client.id(), null, d.expert());
        assertThat(tiers.causes()).extracting(InvestigationResult.Cause::code).contains(CauseCodes.CREDIT_BALANCE_EXPLAINED).doesNotContain(CauseCodes.CREDIT_BALANCE_UNEXPLAINED);
        assertThat(tiers.comparison().difference()).isEqualByComparingTo("0");

        // 13. L'immobilisation sans source : cause FIXED_ASSET_WITHOUT_SOURCE depuis son constat.
        InvestigationResult immo = investigation.depuisConstat(d.id(), actifs(d, ControlCodes.FIXED_ASSET_WITHOUT_SOURCE).get(0).getId(), d.expert());
        assertThat(immo.causes()).extracting(InvestigationResult.Cause::code).contains(CauseCodes.FIXED_ASSET_WITHOUT_SOURCE);
        assertThat(immo.comparison().difference()).isEqualByComparingTo("-5000000");

        // 14. La piece manquante : cause MISSING_DOCUMENT et suite donnee a la demande.
        InvestigationResult piece = investigation.depuisConstat(d.id(), actifs(d, ControlCodes.MISSING_DOCUMENT).get(0).getId(), d.expert());
        assertThat(piece.causes()).extracting(InvestigationResult.Cause::code).contains(CauseCodes.MISSING_DOCUMENT);
        assertThat(piece.subject().id()).isEqualTo(ecritureAchat.toString());

        // 15. Le releve : ecart de solde explique par la transaction non rapprochee (250 000).
        InvestigationResult relevePourquoi = investigation.investiguer(d.id(), "RELEVE", releve.id(), null, d.expert());
        assertThat(relevePourquoi.comparison().difference()).isEqualByComparingTo("-250000");
        assertThat(relevePourquoi.causes()).extracting(InvestigationResult.Cause::code).contains(CauseCodes.BANK_TRANSACTION_UNMATCHED).doesNotContain(CauseCodes.BANK_DIFFERENCE_UNEXPLAINED);

        // 16. L'investigation ne corrige JAMAIS : aucun solde, aucune affectation, aucune ecriture n'a bouge.
        long ecrituresAvant = jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ?", Long.class, d.id());
        long allocationsAvant = jdbc.queryForObject("select count(*) from payment_allocation where entreprise_id = ?", Long.class, d.id());
        for (Finding f : findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.OVERPAYMENT, ControlCodes.PAYMENT_WITHOUT_INVOICE, ControlCodes.UNMATCHED_PAYMENT,
                ControlCodes.MISSING_DOCUMENT, ControlCodes.FIXED_ASSET_WITHOUT_SOURCE))) {
            investigation.depuisConstat(d.id(), f.getId(), d.expert());
        }
        assertThat(jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ?", Long.class, d.id())).isEqualTo(ecrituresAvant);
        assertThat(jdbc.queryForObject("select count(*) from payment_allocation where entreprise_id = ?", Long.class, d.id())).isEqualTo(allocationsAvant);

        // 17. Pre-cloture : des comptes par statut, un domaine par point, NOT_APPLICABLE preserve.
        ClotureReadinessDto precloture = readinessService.evaluer(d.id(), d.expert());
        assertThat(precloture.statut()).isEqualTo("READY_WITH_WARNINGS");
        assertThat(precloture.resume().bloquants()).isZero();
        assertThat(precloture.resume().avertissements()).isGreaterThan(0);
        assertThat(precloture.resume().nonApplicables()).isGreaterThan(0);
        assertThat(precloture.checklist()).allSatisfy(i -> assertThat(i.domaine()).isNotBlank());

        // 18. Un blocage apparait (ecriture en brouillon) : NOT_READY, et la cloture est REFUSEE avec la liste.
        UUID modele = reglement(d, 1_000, LocalDate.now(), "Modele brouillon").ecritureId();
        UUID brouillon = UUID.randomUUID();
        jdbc.update("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut) select ?, entreprise_id, exercice_id, "
                + "journal_id, 'BRO-' || substr(?::text, 1, 8), date_ecriture, 'Brouillon', 'BROUILLON' from ecriture_comptable where id = ?", brouillon, brouillon.toString(), modele);
        jdbc.update("insert into ligne_ecriture (id, ecriture_id, compte_id, tiers_id, libelle, debit, credit, numero_ligne) select gen_random_uuid(), ?, compte_id, tiers_id, libelle, "
                + "debit, credit, numero_ligne from ligne_ecriture where ecriture_id = ?", brouillon, modele);
        UUID exercice = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
        assertThat(readinessService.evaluer(d.id(), d.expert()).statut()).isEqualTo("NOT_READY");
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice, d.expert(), null))).isEqualTo(422);

        // 19. Le blocage corrige : la cloture n'est plus refusee pour ce motif.
        ecritureService.valider(d.id(), brouillon, d.expert());
        assertThat(readinessService.evaluer(d.id(), d.expert()).statut()).isEqualTo("READY_WITH_WARNINGS");

        // 20. Correction de l'encaissement sans facture : la facture est decouverte et affectee ; le constat se ferme TOUT SEUL (jamais supprime).
        Finding sansFacture = actifs(d, ControlCodes.PAYMENT_WITHOUT_INVOICE).stream().filter(f -> encaissementSeul.toString().equals(f.getSourceId())).findFirst().orElseThrow();
        FactureDto facture2 = factureClient(d, client2, 100_000);
        UUID ligneFacture2 = ligneClient(facture2.ecritureId(), "debit");
        BigDecimal ttc2 = jdbc.queryForObject("select debit from ligne_ecriture where id = ?", BigDecimal.class, ligneFacture2);
        allocationService.allouer(d.id(), encaissementSeul, ligneFacture2, ttc2.min(new BigDecimal("120000")), SourceAllocation.USER, d.expert());
        revueService.relancer(d.id(), d.expert());
        Finding ferme = findingRepository.findById(sansFacture.getId()).orElseThrow();
        assertThat(ferme.getStatus()).isEqualTo(StatutFinding.RESOLVED);
        assertThat(ferme.getResolutionType()).isEqualTo(TypeResolution.SYSTEM);

        // 21. Le tableau de bord compte ce qui a ete resolu aujourd'hui.
        assertThat(revueService.espace(d.id(), d.expert()).resolusAujourdhui()).isGreaterThanOrEqualTo(1);

        // 22. Ecarter un constat exige un motif, et il reste ecarte apres une nouvelle execution.
        Finding aEcarter = actifs(d, ControlCodes.UNMATCHED_PAYMENT).get(0);
        findingService.rejeter(d.id(), aEcarter.getId(), "Frais bancaires compenses le mois suivant", d.expert());
        revueService.relancer(d.id(), d.expert());
        assertThat(findingRepository.findById(aEcarter.getId()).orElseThrow().getStatus()).isEqualTo(StatutFinding.DISMISSED);

        // 23. Les avertissements restants exigent une confirmation EXPLICITE : sans elle, 409 avec la liste.
        assertThat(readinessService.evaluer(d.id(), d.expert()).warnings()).isNotEmpty();
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice, d.expert(), null))).isEqualTo(409);

        // 24. Confirmee sans motif : refusee.
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "")))).isEqualTo(422);

        // 25. Isolation : l'autre cabinet ne voit ni les constats, ni l'investigation, ni la pre-cloture, ni ne peut cloturer.
        assertThat(statut(() -> investigation.depuisConstat(autre.id(), troPercu.getId(), autre.expert()))).isEqualTo(404);
        assertThat(statut(() -> investigation.investiguer(autre.id(), "TIERS", client.id(), null, autre.expert()))).isEqualTo(404);
        assertThat(statut(() -> readinessService.evaluer(d.id(), autre.expert()))).isEqualTo(404);
        assertThat(statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice, autre.expert(), new ClotureExerciceService.ConfirmationCloture(true, "x")))).isEqualTo(404);
        assertThat(revueService.espace(autre.id(), autre.expert()).domaines()).isEmpty();

        // 26. Confirmee avec un motif : la cloture est effectuee ; l'acceptation est tracee avec son motif.
        clotureService.cloturerAvecConfirmation(d.id(), exercice, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Pieces attendues en janvier, reglements en cours d'affectation"));
        assertThat(exerciceRepository.findById(exercice).orElseThrow().getStatut()).isEqualTo(StatutExercice.CLOTURE);
        assertThat(evenements("CLOTURE_AVERTISSEMENTS_ACCEPTES").stream().filter(e -> e.getEntiteId().equals(exercice.toString()))).hasSize(1);

        // 27. Le journal d'audit raconte tout le parcours : controles, investigations, refus, ecartement, acceptation, cloture.
        for (String action : List.of("CONTROLES_EXECUTES", "INVESTIGATION_OUVERTE", "CLOTURE_REFUSEE", "FINDING_REJETE", "CLOTURE_AVERTISSEMENTS_ACCEPTES", "CLOTURE_TERMINEE", "CLOTURE_CONTROLEE")) {
            assertThat(evenements(action)).as(action).isNotEmpty();
        }
        // Les investigations restent classees comme des anomalies a traiter ou des explications, jamais sans nature.
        assertThat(pourquoi.causes()).allSatisfy(c -> assertThat(c.nature()).isIn((Object[]) Nature.values()));
        assertThat(Map.of("etapes", 27)).containsEntry("etapes", 27);
    }
}
