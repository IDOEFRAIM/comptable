package com.example.comptable.comptabilite;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.lineage.AccountingLineageDto;
import com.example.comptable.comptabilite.lineage.AccountingLineageService;
import com.example.comptable.comptabilite.operation.achat.AchatMultiLigneOperationRequest;
import com.example.comptable.comptabilite.operation.achat.LigneAchatOperationRequest;
import com.example.comptable.comptabilite.operation.achat.NatureAchat;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.service.ComptabilisationService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.ImmobilisationService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.ComptabiliserAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.GenererDotationsResultDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
import com.example.comptable.comptabilite.web.dto.LierAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.comptabilite.web.dto.MettreEnServiceRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.TypeArticle;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.service.ArticleService;
import com.example.comptable.stock.service.MouvementStockService;
import com.example.comptable.stock.web.dto.ArticleDto;
import com.example.comptable.stock.web.dto.CreerArticleRequest;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B / B0 sur PostgreSQL reel : chaque consequence comptable a une cause metier identifiable, une meme
 * cause ne produit jamais deux ecritures (y compris entre flux differents), facture et immobilisation ne
 * comptent pas deux fois l'acquisition, et la navigation cause <-> ecriture est exacte et isolee par entreprise.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class AccountingLineageTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntrepriseService entrepriseService;
    @Autowired private EntrepriseRepository entrepriseRepository;
    @Autowired private TiersService tiersService;
    @Autowired private CompteComptableRepository compteComptableRepository;
    @Autowired private JournalRepository journalRepository;
    @Autowired private ExerciceComptableRepository exerciceComptableRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ComptabilisationService comptabilisationService;
    @Autowired private EcritureComptableService ecritureComptableService;
    @Autowired private EcritureComptableRepository ecritureComptableRepository;
    @Autowired private AccountingOperationEngine engine;
    @Autowired private AccountingOperationProposalRepository proposalRepository;
    @Autowired private ImmobilisationService immobilisationService;
    @Autowired private ArticleService articleService;
    @Autowired private MouvementStockService mouvementStockService;
    @Autowired private AccountingLineageService lineageService;
    @Autowired private JdbcTemplate jdbc;

    private UtilisateurPrincipal principalA;
    private UtilisateurPrincipal principalB;
    private EntrepriseDto entrepriseA;
    private EntrepriseDto entrepriseB;
    private TiersDto fournisseurA;

    @BeforeEach
    void amorcer() {
        principalA = creerPrincipal();
        principalB = creerPrincipal();
        entrepriseA = creerEntreprise(principalA);
        entrepriseB = creerEntreprise(principalB);
        fournisseurA = tiersService.creer(entrepriseA.id(), TypeTiers.FOURNISSEUR, "Fournisseur A", null, principalA);
    }

    // ================================================================= meme source rejouee

    @Test
    void la_base_garantit_une_seule_consequence_active_par_cause() {
        List<String> index = jdbc.queryForList("select indexdef from pg_indexes where indexname = 'uq_aop_source_event'", String.class);

        assertThat(index).singleElement().satisfies(def -> assertThat(def).contains("UNIQUE").contains("source_event").contains("REJECTED"));
        assertThat(jdbc.queryForObject("select count(*) from accounting_operation_proposal where source_type is null or source_id is null or source_event is null", Long.class)).isZero();
    }

    @Test
    void meme_source_rejouee_vers_le_moteur_ne_cree_jamais_de_seconde_ecriture() {
        AccountingSource cause = AccountingSource.de("RELEVE_TEST", UUID.randomUUID(), "COMPTABILISATION");
        AccountingOperationProposalDto premiere = engine.proposerEtValiderImmediatement(entrepriseA.id(), AccountingOperationType.ACHAT,
                fournisseurA == null ? null : tiers(fournisseurA), LocalDate.now(), "Achat", null, resultatAchat(100000), cause, principalA);
        long avant = nombreEcritures(entrepriseA);

        AccountingOperationProposalDto rejeu = engine.proposerEtValiderImmediatement(entrepriseA.id(), AccountingOperationType.ACHAT,
                tiers(fournisseurA), LocalDate.now(), "Achat rejoue", null, resultatAchat(100000), cause, principalA);

        assertThat(rejeu.id()).isEqualTo(premiere.id());
        assertThat(rejeu.ecritureId()).isEqualTo(premiere.ecritureId());
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant);
        assertThat(proposalRepository.findByEntrepriseIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc(entrepriseA.id(), "RELEVE_TEST", cause.id())).hasSize(1);
    }

    @Test
    void le_flux_document_et_le_flux_ia_partagent_la_meme_cause_donc_un_document_n_est_jamais_comptabilise_deux_fois() {
        Document document = documentRepository.save(document(entrepriseA, "100000", "18000", "118000"));
        // Flux "IA" : le moteur est appele avec la cause DOCUMENT/COMPTABILISATION sans toucher document.ecritureId.
        AccountingOperationProposalDto ia = engine.proposerEtValiderImmediatementSysteme(entrepriseA.id(), AccountingOperationType.ACHAT,
                tiers(fournisseurA), LocalDate.now(), "Facture IA", document.getId().toString(), resultatAchat(118000),
                AccountingSource.document(document.getId()));
        long avant = nombreEcritures(entrepriseA);

        // Flux manuel sur le MEME document : retrouve l'ecriture existante au lieu d'en creer une seconde.
        EcritureComptableDto manuel = comptabilisationService.comptabiliser(entrepriseA.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseurA.id(), compte(entrepriseA, "601"), null, null), principalA);

        assertThat(manuel.id()).isEqualTo(ia.ecritureId());
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant);
    }

    @Test
    void un_mouvement_de_stock_n_a_qu_une_ecriture_et_sa_cause_est_navigable() {
        ArticleDto article = articleService.creer(entrepriseA.id(), new CreerArticleRequest("ART-L", "Article", null, TypeArticle.MARCHANDISE, "u",
                BigDecimal.TEN, BigDecimal.ZERO, compte(entrepriseA, "311"), compte(entrepriseA, "601"), compte(entrepriseA, "701"),
                compte(entrepriseA, "6031"), null, null, null), principalA);
        MouvementStockDto mouvement = mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(article.id(),
                TypeMouvementStock.ENTREE, new BigDecimal("4"), new BigDecimal("500"), LocalDate.now(), "Entree"), principalA);

        AccountingLineageDto.DepuisSource depuisSource = lineageService.depuisSource(entrepriseA.id(), "MOUVEMENT_STOCK", mouvement.id(), principalA);

        assertThat(depuisSource.consequences()).singleElement().satisfies(c -> {
            assertThat(c.source().event()).isEqualTo("ENTREE");
            assertThat(c.type()).isEqualTo("STOCK_ENTREE");
            assertThat(c.ecritureId()).isEqualTo(mouvement.ecritureId());
        });
        AccountingLineageDto.DepuisEcriture depuisEcriture = lineageService.depuisEcriture(entrepriseA.id(), mouvement.ecritureId(), principalA);
        assertThat(depuisEcriture.origine()).isEqualTo("MOTEUR");
        assertThat(depuisEcriture.proposition().source().type()).isEqualTo("MOUVEMENT_STOCK");
        assertThat(depuisEcriture.proposition().source().id()).isEqualTo(mouvement.id());
    }

    // ================================================================= facture <-> immobilisation

    @Test
    void acquisition_appelee_deux_fois_ne_cree_qu_une_ecriture() {
        ImmobilisationDto immo = creerImmobilisation("Machine", "5000000");
        long avant = nombreEcritures(entrepriseA);

        ImmobilisationDto acquise = immobilisationService.comptabiliserAcquisition(entrepriseA.id(), immo.id(),
                new ComptabiliserAcquisitionRequest(fournisseurA.id(), null, null), principalA);

        assertThat(acquise.acquisitionProposalId()).isNotNull();
        assertThat(acquise.acquisitionSource()).isEqualTo("IMMOBILISATION/" + immo.id() + "/ACQUISITION");
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant + 1);
        assertThat(statut(() -> immobilisationService.comptabiliserAcquisition(entrepriseA.id(), immo.id(),
                new ComptabiliserAcquisitionRequest(fournisseurA.id(), null, null), principalA))).isEqualTo(409);
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant + 1);
    }

    @Test
    void facture_immobilisation_deja_comptabilisee_l_immobilisation_la_reference_et_ne_recompte_pas() {
        UUID factureId = UUID.randomUUID();
        facturerImmobilisation(factureId, "5000000", "900000");
        ImmobilisationDto immo = creerImmobilisation("Camion", "5000000");
        long avant = nombreEcritures(entrepriseA);

        // selecteur : la facture est proposee avec sa valeur disponible, aucun identifiant a saisir
        var candidats = immobilisationService.candidatsAcquisition(entrepriseA.id(), immo.id(), principalA);
        assertThat(candidats).singleElement().satisfies(c -> {
            assertThat(c.sourceType()).isEqualTo("FACTURE_FOURNISSEUR");
            assertThat(c.sourceId()).isEqualTo(factureId);
            assertThat(c.capaciteRestante()).isEqualByComparingTo("5000000");
            assertThat(c.suffisant()).isTrue();
        });

        ImmobilisationDto liee = immobilisationService.lierAcquisition(entrepriseA.id(), immo.id(),
                new LierAcquisitionRequest("FACTURE_FOURNISSEUR", factureId), principalA);

        assertThat(liee.acquisitionProposalId()).isNotNull();
        assertThat(liee.acquisitionSource()).isEqualTo("FACTURE_FOURNISSEUR/" + factureId + "/VALIDATION");
        assertThat(liee.acquisitionEcritureId()).isNotNull();
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant);
        // l'immobilisation ne comptabilise plus sa propre acquisition
        assertThat(statut(() -> immobilisationService.comptabiliserAcquisition(entrepriseA.id(), immo.id(),
                new ComptabiliserAcquisitionRequest(fournisseurA.id(), null, null), principalA))).isEqualTo(409);
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant);
        // navigation dans les deux sens, sans heuristique
        AccountingLineageDto.DepuisEcriture depuis = lineageService.depuisEcriture(entrepriseA.id(), liee.acquisitionEcritureId(), principalA);
        assertThat(depuis.proposition().source().type()).isEqualTo("FACTURE_FOURNISSEUR");
        assertThat(depuis.proposition().source().id()).isEqualTo(factureId);
        assertThat(lineageService.depuisSource(entrepriseA.id(), "FACTURE_FOURNISSEUR", factureId, principalA).consequences()).hasSize(1);
    }

    @Test
    void sans_lien_une_facture_candidate_bloque_le_double_comptage_sauf_confirmation_explicite() {
        facturerImmobilisation(UUID.randomUUID(), "5000000", "900000");
        ImmobilisationDto immo = creerImmobilisation("Camion", "5000000");

        ResponseStatusException blocage = refus(() -> immobilisationService.comptabiliserAcquisition(entrepriseA.id(), immo.id(),
                new ComptabiliserAcquisitionRequest(fournisseurA.id(), null, null), principalA));

        assertThat(blocage.getStatusCode().value()).isEqualTo(409);
        assertThat(blocage.getReason()).contains("facture").contains("double comptage");
        // confirmation explicite (autre achat de meme valeur) : autorisee et tracee
        ImmobilisationDto confirme = immobilisationService.comptabiliserAcquisition(entrepriseA.id(), immo.id(),
                new ComptabiliserAcquisitionRequest(fournisseurA.id(), null, true), principalA);
        assertThat(confirme.acquisitionProposalId()).isNotNull();
    }

    @Test
    void lier_une_facture_trop_petite_ou_inexistante_ou_deja_epuisee_est_refuse() {
        UUID factureId = UUID.randomUUID();
        facturerImmobilisation(factureId, "5000000", "900000");
        ImmobilisationDto premiere = creerImmobilisation("Premiere", "3000000");
        ImmobilisationDto seconde = creerImmobilisation("Seconde", "3000000");

        immobilisationService.lierAcquisition(entrepriseA.id(), premiere.id(), new LierAcquisitionRequest("FACTURE_FOURNISSEUR", factureId), principalA);

        assertThat(statut(() -> immobilisationService.lierAcquisition(entrepriseA.id(), seconde.id(),
                new LierAcquisitionRequest("FACTURE_FOURNISSEUR", factureId), principalA))).isEqualTo(422); // 3M + 3M > 5M facturés
        assertThat(statut(() -> immobilisationService.lierAcquisition(entrepriseA.id(), seconde.id(),
                new LierAcquisitionRequest("FACTURE_FOURNISSEUR", UUID.randomUUID()), principalA))).isEqualTo(422);
        assertThat(statut(() -> immobilisationService.lierAcquisition(entrepriseA.id(), premiere.id(),
                new LierAcquisitionRequest("FACTURE_FOURNISSEUR", factureId), principalA))).isEqualTo(409);
    }

    // ================================================================= dotations par annee

    @Test
    void la_dotation_de_l_annee_N_est_distincte_de_celle_de_N_plus_1() {
        int annee = LocalDate.now().getYear();
        ExerciceComptable suivant = new ExerciceComptable();
        suivant.setEntreprise(entrepriseRepository.getReferenceById(entrepriseA.id()));
        suivant.setDateDebut(LocalDate.of(annee + 1, 1, 1));
        suivant.setDateFin(LocalDate.of(annee + 1, 12, 31));
        exerciceComptableRepository.save(suivant);
        ImmobilisationDto immo = creerImmobilisation("Camion", "5000000");
        immobilisationService.mettreEnService(entrepriseA.id(), immo.id(), new MettreEnServiceRequest(LocalDate.of(annee, 1, 1)), principalA);

        GenererDotationsResultDto n = immobilisationService.genererDotations(entrepriseA.id(), annee, principalA);
        GenererDotationsResultDto n1 = immobilisationService.genererDotations(entrepriseA.id(), annee + 1, principalA);

        assertThat(n.ecritureIds()).hasSize(1);
        assertThat(n1.ecritureIds()).hasSize(1);
        assertThat(n1.ecritureIds().get(0)).isNotEqualTo(n.ecritureIds().get(0));
        assertThat(lineageService.depuisSource(entrepriseA.id(), "IMMOBILISATION", immo.id(), principalA).consequences())
                .extracting(c -> c.source().event()).containsExactlyInAnyOrder("DOTATION_" + annee, "DOTATION_" + (annee + 1));
        // rejeu : aucune nouvelle consequence
        assertThat(immobilisationService.genererDotations(entrepriseA.id(), annee, principalA).ecritureIds()).isEmpty();
    }

    // ================================================================= navigation

    @Test
    void ecriture_issue_d_un_document_remonte_a_la_proposition_a_la_source_et_au_document() {
        Document document = documentRepository.save(document(entrepriseA, "100000", "18000", "118000"));
        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entrepriseA.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseurA.id(), compte(entrepriseA, "601"), null, null), principalA);

        AccountingLineageDto.DepuisEcriture depuis = lineageService.depuisEcriture(entrepriseA.id(), ecriture.id(), principalA);

        assertThat(depuis.origine()).isEqualTo("MOTEUR");
        assertThat(depuis.proposition().source()).isEqualTo(new AccountingLineageDto.Source("DOCUMENT", document.getId(), "COMPTABILISATION"));
        assertThat(depuis.document().id()).isEqualTo(document.getId());
        AccountingLineageDto.DepuisSource retour = lineageService.depuisSource(entrepriseA.id(), "DOCUMENT", document.getId(), principalA);
        assertThat(retour.consequences()).singleElement().satisfies(c -> assertThat(c.ecritureId()).isEqualTo(ecriture.id()));
    }

    @Test
    void une_ecriture_manuelle_est_identifiee_comme_telle_sans_proposition() {
        UUID journalOd = journalRepository.findByEntrepriseIdAndCode(entrepriseA.id(), "OD").orElseThrow().getId();
        EcritureComptableDto manuelle = ecritureComptableService.creerManuelle(entrepriseA.id(), new CreerEcritureRequest(journalOd, LocalDate.now(),
                "Correction", null, List.of(
                new LigneEcritureRequest(compte(entrepriseA, "601"), "d", new BigDecimal("1000"), BigDecimal.ZERO, null),
                new LigneEcritureRequest(compte(entrepriseA, "521"), "c", BigDecimal.ZERO, new BigDecimal("1000"), null))), principalA);

        AccountingLineageDto.DepuisEcriture depuis = lineageService.depuisEcriture(entrepriseA.id(), manuelle.id(), principalA);

        assertThat(depuis.origine()).isEqualTo("SAISIE_MANUELLE");
        assertThat(depuis.proposition()).isNull();
    }

    // ================================================================= isolation

    @Test
    void entreprise_B_ne_voit_ni_ne_lie_rien_de_A() {
        UUID factureId = UUID.randomUUID();
        facturerImmobilisation(factureId, "5000000", "900000");
        ImmobilisationDto immoA = creerImmobilisation("Camion A", "5000000");
        MouvementStockDto mouvementA = mouvementPourA();
        EcritureComptableDto ecritureA = ecritureComptableService.lister(entrepriseA.id(), principalA).get(0);

        // B ne peut ni lire la chaine de A, ni lier un bien de A, ni lier un bien a une facture de A
        assertThat(statut(() -> lineageService.depuisEcriture(entrepriseB.id(), ecritureA.id(), principalB))).isEqualTo(404);
        assertThat(lineageService.depuisSource(entrepriseB.id(), "MOUVEMENT_STOCK", mouvementA.id(), principalB).consequences()).isEmpty();
        assertThat(statut(() -> lineageService.depuisEcriture(entrepriseA.id(), ecritureA.id(), principalB))).isEqualTo(404);
        assertThat(statut(() -> immobilisationService.lierAcquisition(entrepriseB.id(), immoA.id(),
                new LierAcquisitionRequest("FACTURE_FOURNISSEUR", factureId), principalB))).isEqualTo(404);
        ImmobilisationDto immoB = immobilisationService.creer(entrepriseB.id(), new CreerImmobilisationRequest(compte(entrepriseB, "244"),
                compte(entrepriseB, "281"), "Machine B", TypeImmobilisation.CORPORELLE, LocalDate.now(), new BigDecimal("5000000"), 5,
                ModeAmortissement.LINEAIRE), principalB);
        assertThat(statut(() -> immobilisationService.lierAcquisition(entrepriseB.id(), immoB.id(),
                new LierAcquisitionRequest("FACTURE_FOURNISSEUR", factureId), principalB))).isEqualTo(422); // la facture de A est invisible pour B
    }

    // ================================================================= utilitaires

    private MouvementStockDto mouvementPourA() {
        ArticleDto article = articleService.creer(entrepriseA.id(), new CreerArticleRequest("ART-M", "Article", null, TypeArticle.MARCHANDISE, "u",
                BigDecimal.TEN, BigDecimal.ZERO, compte(entrepriseA, "311"), compte(entrepriseA, "601"), compte(entrepriseA, "701"),
                compte(entrepriseA, "6031"), null, null, null), principalA);
        return mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(article.id(), TypeMouvementStock.ENTREE,
                new BigDecimal("2"), new BigDecimal("100"), LocalDate.now(), "Entree"), principalA);
    }

    /** Une facture fournisseur contenant une ligne d'immobilisation, comptabilisee par le moteur (cause FACTURE_FOURNISSEUR/VALIDATION). */
    private void facturerImmobilisation(UUID factureId, String ht, String tva) {
        BigDecimal taux = new BigDecimal(tva).multiply(new BigDecimal("100")).divide(new BigDecimal(ht), 2, java.math.RoundingMode.HALF_UP);
        engine.proposerAchatEtValiderImmediatement(entrepriseA.id(), new AchatMultiLigneOperationRequest(fournisseurA.id(), LocalDate.now(),
                "FF-" + factureId.toString().substring(0, 6), "Facture immobilisation", List.of(new LigneAchatOperationRequest("Camion",
                NatureAchat.IMMOBILISATION, new BigDecimal(ht), taux, compte(entrepriseA, "244"), null)),
                null, AccountingSource.de("FACTURE_FOURNISSEUR", factureId, "VALIDATION")), principalA);
    }

    private ImmobilisationDto creerImmobilisation(String libelle, String valeur) {
        return immobilisationService.creer(entrepriseA.id(), new CreerImmobilisationRequest(compte(entrepriseA, "244"), compte(entrepriseA, "281"),
                libelle, TypeImmobilisation.CORPORELLE, LocalDate.now().withDayOfYear(1), new BigDecimal(valeur), 5, ModeAmortissement.LINEAIRE), principalA);
    }

    private com.example.comptable.comptabilite.domain.Tiers tiers(TiersDto dto) {
        return tiersRepositoryRef(dto.id());
    }

    @Autowired private com.example.comptable.comptabilite.repository.TiersRepository tiersRepository;

    private com.example.comptable.comptabilite.domain.Tiers tiersRepositoryRef(UUID id) {
        return tiersRepository.findById(id).orElseThrow();
    }

    private ResultatRegle resultatAchat(int ttc) {
        CompteComptable charge = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseA.id(), "601").orElseThrow();
        CompteComptable compteFournisseur = compteComptableRepository.findById(fournisseurA.compteComptableId()).orElseThrow();
        return new ResultatRegle(TypeJournal.ACHATS, List.of(
                AccountingOperationProposalLigne.debit(charge, new BigDecimal(ttc), "Charge"),
                AccountingOperationProposalLigne.credit(compteFournisseur, new BigDecimal(ttc), "Fournisseur")), List.of(), List.of());
    }

    private long nombreEcritures(EntrepriseDto entreprise) {
        return ecritureComptableRepository.findAll().stream().filter(e -> e.getEntreprise().getId().equals(entreprise.id())).count();
    }

    private UUID compte(EntrepriseDto entreprise, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), numero).orElseThrow().getId();
    }

    private ResponseStatusException refus(Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            return e;
        }
        throw new AssertionError("Une ResponseStatusException etait attendue");
    }

    private int statut(Supplier<?> action) {
        try {
            action.get();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
        throw new AssertionError("Une ResponseStatusException etait attendue");
    }

    private Document document(EntrepriseDto entreprise, String ht, String tva, String ttc) {
        Document document = new Document();
        document.setTenant(entrepriseRepository.findById(entreprise.id()).orElseThrow().getTenant());
        document.setEntreprise(entrepriseRepository.getReferenceById(entreprise.id()));
        document.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        document.setCanalSource(CanalSource.WEB);
        document.setCheminFichier("test/facture.pdf");
        document.setNomFichierOriginal("facture-test.pdf");
        document.setMontantHt(new BigDecimal(ht));
        document.setMontantTva(new BigDecimal(tva));
        document.setMontantTtc(new BigDecimal(ttc));
        document.setDatePiece(LocalDate.now());
        return document;
    }

    private EntrepriseDto creerEntreprise(UtilisateurPrincipal principal) {
        return entrepriseService.creer(new CreerEntrepriseRequest("PME Lineage " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), principal);
    }

    private UtilisateurPrincipal creerPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Lineage");
        tenant.setSlug("cabinet-lineage-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("lineage-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Lineage");
        utilisateur.setRoles(Set.of(role));
        return new UtilisateurPrincipal(utilisateurRepository.save(utilisateur));
    }
}
