package com.example.comptable.controle;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeResolution;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.audit.repository.AuditEventRepository;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.achat.AchatMultiLigneOperationRequest;
import com.example.comptable.comptabilite.operation.achat.LigneAchatOperationRequest;
import com.example.comptable.comptabilite.operation.achat.NatureAchat;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.ImmobilisationService;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.ComptabiliserAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.comptabilite.web.dto.LierAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.comptabilite.web.dto.ReleveBancaireDto;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.revue.RevueComptableService;
import com.example.comptable.controle.revue.RevueDto;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.DocumentLiaisonService;
import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.dossier.web.dto.ControlItem;
import com.example.comptable.entreprise.domain.Entreprise;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B sur PostgreSQL reel : le dossier est piloté PAR EXCEPTION. Source -> proposition -> ecriture ->
 * controle -> Finding -> revue ; une cause qui disparait ferme son constat (resolution SYSTEM, jamais supprime),
 * un constat ecarte reste auditable, les cas certains sont automatises, les ambigus jamais tranches.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class DossierPiloteParExceptionTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntrepriseService entrepriseService;
    @Autowired private EntrepriseRepository entrepriseRepository;
    @Autowired private TiersService tiersService;
    @Autowired private CompteComptableRepository compteComptableRepository;
    @Autowired private JournalRepository journalRepository;
    @Autowired private EcritureComptableRepository ecritureComptableRepository;
    @Autowired private EcritureComptableService ecritureComptableService;
    @Autowired private AccountingOperationEngine engine;
    @Autowired private ImmobilisationService immobilisationService;
    @Autowired private RapprochementBancaireService rapprochementService;
    @Autowired private LigneReleveRepository ligneReleveRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentLiaisonService documentLiaisonService;
    @Autowired private ControlRunService controlRunService;
    @Autowired private FindingRepository findingRepository;
    @Autowired private FindingService findingService;
    @Autowired private RevueComptableService revueService;
    @Autowired private ClotureReadinessService clotureReadinessService;
    @Autowired private AuditEventRepository auditEventRepository;
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

    // =====================================================================================================
    // Scenario global : le dossier d'une entreprise, du premier achat a la pre-cloture (22 etapes)
    // =====================================================================================================

    @Test
    void dossier_complet_pilote_par_exception() {
        // 1-3. entreprise A (PCE seede), parametrage utilisable, fournisseur
        assertThat(compte(entrepriseA, "244")).isNotNull();
        assertThat(compte(entrepriseA, "521")).isNotNull();
        assertThat(fournisseurA.id()).isNotNull();

        // 4. facture fournisseur d'immobilisation comptabilisee par le moteur (cause FACTURE_FOURNISSEUR/VALIDATION)
        UUID factureId = UUID.randomUUID();
        EcritureComptableDto ecritureFacture = facturer(factureId, "5000000", "900000");

        // 5. l'immobilisation est creee a part : aucune source comptable -> constat FIXED_ASSET_WITHOUT_SOURCE
        ImmobilisationDto camion = creerImmobilisation("Camion", "5000000");
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(entrepriseA, ControlCodes.FIXED_ASSET_WITHOUT_SOURCE)).singleElement()
                .satisfies(f -> assertThat(f.getSourceId()).isEqualTo(camion.id().toString()));

        // 6. liaison a la facture : AUCUNE seconde ecriture d'acquisition, et le constat se ferme tout seul
        long avantLiaison = nombreEcritures(entrepriseA);
        immobilisationService.lierAcquisition(entrepriseA.id(), camion.id(), new LierAcquisitionRequest("FACTURE_FOURNISSEUR", factureId), principalA);
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avantLiaison);
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(entrepriseA, ControlCodes.FIXED_ASSET_WITHOUT_SOURCE)).isEmpty();
        Finding ferme = tous(entrepriseA, ControlCodes.FIXED_ASSET_WITHOUT_SOURCE).get(0);
        assertThat(ferme.getStatus()).isEqualTo(StatutFinding.RESOLVED);
        assertThat(ferme.getResolutionType()).isEqualTo(TypeResolution.SYSTEM); // resolu par le systeme, jamais supprime
        assertThat(ferme.getResolvedAt()).isNotNull();

        // 7. la facture n'a aucune piece justificative -> MISSING_DOCUMENT sur son ecriture
        assertThat(actifs(entrepriseA, ControlCodes.MISSING_DOCUMENT)).singleElement().satisfies(f -> {
            assertThat(f.getSourceType()).isEqualTo("ECRITURE_COMPTABLE");
            assertThat(f.getSourceId()).isEqualTo(ecritureFacture.id().toString());
            assertThat(f.getCategory()).isEqualTo(ControlCategory.DOCUMENT);
        });

        // 8. releve bancaire : deux transactions sans aucune ecriture -> UNMATCHED_PAYMENT x2 (une decision par ligne)
        ReleveBancaireDto releve = importerReleve("-150000", "-100000");
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(entrepriseA, ControlCodes.UNMATCHED_PAYMENT)).hasSize(2);

        // 9. pre-cloture : meme source de verite (Findings + controle propre a la cloture), avec soldes et ids de navigation
        ClotureReadinessDto precloture = clotureReadinessService.evaluer(entrepriseA.id(), principalA);
        ControlItem banque = precloture.checklist().stream().filter(i -> i.code().equals("banque")).findFirst().orElseThrow();
        assertThat(banque.statut()).isEqualTo("WARNING");
        assertThat(banque.relatedEntityIds()).hasSize(2);
        assertThat(banque.metadata().get("nbLignesNonPointees")).isEqualTo(2);
        assertThat(banque.explanation()).contains("solde comptable").contains("ecart");
        @SuppressWarnings("unchecked")
        Map<String, Object> detailReleve = ((List<Map<String, Object>>) banque.metadata().get("releves")).get(0);
        assertThat(new BigDecimal(String.valueOf(detailReleve.get("ecartSolde"))).abs()).isEqualByComparingTo("250000");
        assertThat(detailReleve.get("ecartExpliqueParNonPointees")).isEqualTo(true);

        // 10. un reglement de 150 000 est saisi puis les controles sont relances : cas CERTAIN -> pointe automatiquement
        reglerFournisseur("150000");
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(entrepriseA, ControlCodes.UNMATCHED_PAYMENT)).hasSize(1);
        assertThat(ligneReleveRepository.findAll().stream().filter(l -> l.getMontant().compareTo(new BigDecimal("-150000")) == 0)
                .allMatch(l -> l.getLigneEcritureId() != null)).isTrue();
        assertThat(evenements("BANK_TRANSACTION_MATCHED_AUTOMATICALLY")).isNotEmpty();

        // 11. deux reglements de 100 000 : la ligne est AMBIGUE, le systeme ne choisit jamais le premier
        reglerFournisseur("100000");
        reglerFournisseur("100000");
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(entrepriseA, ControlCodes.UNMATCHED_PAYMENT)).isEmpty();
        List<Finding> ambigus = actifs(entrepriseA, ControlCodes.AMBIGUOUS_BANK_MATCH);
        assertThat(ambigus).singleElement().satisfies(f -> {
            assertThat(f.getEvidenceJson().get("nbCandidats")).isEqualTo(2);
            assertThat(f.getCategory()).isEqualTo(ControlCategory.RECONCILIATION);
        });
        UUID ligneAmbigue = UUID.fromString(ambigus.get(0).getSourceId());
        assertThat(ligneReleveRepository.findById(ligneAmbigue).orElseThrow().getLigneEcritureId()).isNull();

        // 12. le comptable tranche : pointage manuel, puis le constat se ferme au passage suivant
        UUID candidat = UUID.fromString(((Map<?, ?>) ((List<?>) ambigus.get(0).getEvidenceJson().get("candidats")).get(0)).get("ligneEcritureId").toString());
        rapprochementService.pointer(entrepriseA.id(), releve.id(), ligneAmbigue, candidat, principalA);
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(entrepriseA, ControlCodes.AMBIGUOUS_BANK_MATCH)).isEmpty();
        assertThat(tous(entrepriseA, ControlCodes.AMBIGUOUS_BANK_MATCH).get(0).getResolutionType()).isEqualTo(TypeResolution.SYSTEM);

        // 13. on rattache la piece a la facture : MISSING_DOCUMENT se ferme (et reste dans l'historique)
        Document piece = documentRepository.save(document(entrepriseA, "5000000", "900000", "5900000"));
        documentLiaisonService.lierAEcriture(entrepriseA.id(), piece.getId(), ecritureFacture.id(), principalA);
        assertThat(actifs(entrepriseA, ControlCodes.MISSING_DOCUMENT)).isEmpty();
        assertThat(tous(entrepriseA, ControlCodes.MISSING_DOCUMENT)).hasSize(1);
        assertThat(statut(() -> documentLiaisonService.lierAEcriture(entrepriseA.id(), piece.getId(), ecritureFacture.id(), principalA))).isEqualTo(409);

        // 14. incoherence TVA sur une piece : HT 100 000 + TVA 18 000 != TTC 120 000 -> VAT_INCONSISTENCY ; corrigee -> fermee
        Document bancale = documentRepository.save(document(entrepriseA, "100000", "18000", "120000"));
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(entrepriseA, ControlCodes.VAT_INCONSISTENCY)).singleElement().satisfies(f -> {
            assertThat(f.getControlSeverity()).isEqualTo(ControlSeverity.ERROR);
            assertThat(f.getDescription()).contains("118000").contains("120000").contains("2000");
        });
        bancale.setMontantTtc(new BigDecimal("118000"));
        documentRepository.save(bancale);
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(entrepriseA, ControlCodes.VAT_INCONSISTENCY)).isEmpty();

        // 15. double acquisition possible : une 2e facture de 5 M non rattachee + acquisition directe confirmee
        UUID factureDoublon = UUID.randomUUID();
        facturer(factureDoublon, "5000000", "900000");
        ImmobilisationDto doublon = creerImmobilisation("Camion bis", "5000000");
        immobilisationService.comptabiliserAcquisition(entrepriseA.id(), doublon.id(),
                new ComptabiliserAcquisitionRequest(fournisseurA.id(), null, true), principalA);
        controlRunService.executer(entreprise(entrepriseA));
        List<Finding> doubles = actifs(entrepriseA, ControlCodes.POSSIBLE_DOUBLE_ACQUISITION);
        assertThat(doubles).singleElement().satisfies(f -> assertThat(f.getSourceId()).isEqualTo(doublon.id().toString()));

        // 16. ecarter exige un motif ; le constat ecarte n'est PAS supprime et n'est pas recree tant que la cause persiste
        UUID idDouble = doubles.get(0).getId();
        assertThat(statut(() -> findingService.rejeter(entrepriseA.id(), idDouble, "  ", principalA))).isEqualTo(400);
        findingService.rejeter(entrepriseA.id(), idDouble, "Deux camions distincts, confirme avec le client", principalA);
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(entrepriseA, ControlCodes.POSSIBLE_DOUBLE_ACQUISITION)).isEmpty();
        Finding ecarte = findingRepository.findById(idDouble).orElseThrow();
        assertThat(ecarte.getStatus()).isEqualTo(StatutFinding.DISMISSED);
        assertThat(ecarte.getResolutionType()).isEqualTo(TypeResolution.DISMISSED);
        assertThat(ecarte.getResolutionComment()).contains("Deux camions");
        assertThat(tous(entrepriseA, ControlCodes.POSSIBLE_DOUBLE_ACQUISITION)).hasSize(1);

        // 17. dedoublonnage : relancer trois fois ne cree ni ne duplique rien
        long avant = findingRepository.findAll().stream().filter(f -> f.getEntreprise().getId().equals(entrepriseA.id())).count();
        controlRunService.executer(entreprise(entrepriseA));
        controlRunService.executer(entreprise(entrepriseA));
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(findingRepository.findAll().stream().filter(f -> f.getEntreprise().getId().equals(entrepriseA.id())).count()).isEqualTo(avant);
        assertThat(jdbc.queryForObject("select count(*) from (select 1 from finding where status in ('OPEN','IN_PROGRESS') "
                + "group by entreprise_id, control_code, source_type, source_id having count(*) > 1) d", Long.class)).isZero();

        // 18. revue : tuiles coherentes avec les constats actifs, liste filtrable et paginee
        RevueDto.Synthese synthese = revueService.synthese(entrepriseA.id(), principalA);
        assertThat(synthese.totalActifs()).isEqualTo(findingRepository.agregerActifs(entrepriseA.id()).stream().mapToLong(a -> a.nombre()).sum());
        RevueDto.Page pageRapprochements = revueService.lister(entrepriseA.id(), new RevueComptableService.Filtre(RevueComptableService.Tuile.RAPPROCHEMENTS,
                null, null, null, null, null, null, null, null), 0, 10, principalA);
        assertThat(pageRapprochements.contenu()).allSatisfy(c -> assertThat(c.category()).isEqualTo("RECONCILIATION"));
        RevueDto.Page recherche = revueService.lister(entrepriseA.id(), new RevueComptableService.Filtre(null, null, null, null, null,
                ControlCodes.POSSIBLE_DOUBLE_ACQUISITION, null, null, null), 0, 10, principalA);
        assertThat(recherche.total()).isZero(); // ecarte : n'est plus "a traiter"
        RevueDto.Page ecartes = revueService.lister(entrepriseA.id(), new RevueComptableService.Filtre(null, null, null, StatutFinding.DISMISSED, null,
                ControlCodes.POSSIBLE_DOUBLE_ACQUISITION, null, null, null), 0, 10, principalA);
        assertThat(ecartes.total()).isEqualTo(1);
        assertThat(ecartes.contenu().get(0).resolutionComment()).contains("Deux camions");

        // 19. isolation : l'entreprise B n'a aucun constat de A, ne peut ni les lire ni les ecarter
        controlRunService.executer(entreprise(entrepriseB));
        assertThat(findingRepository.findAll().stream().filter(f -> f.getEntreprise().getId().equals(entrepriseB.id()))
                .filter(f -> f.getStatus() == StatutFinding.OPEN)).isEmpty();
        assertThat(revueService.synthese(entrepriseB.id(), principalB).totalActifs()).isZero();
        assertThat(statut(() -> revueService.synthese(entrepriseA.id(), principalB))).isEqualTo(404);
        assertThat(statut(() -> revueService.obtenir(entrepriseB.id(), idDouble, principalB))).isEqualTo(404);
        assertThat(statut(() -> findingService.rejeter(entrepriseB.id(), idDouble, "x", principalB))).isEqualTo(404);

        // 20. pre-cloture : un brouillon bloque, sa validation leve le blocage
        EcritureComptableDto brouillon = saisirBrouillon();
        ClotureReadinessDto bloquee = clotureReadinessService.evaluer(entrepriseA.id(), principalA);
        assertThat(bloquee.statut()).isEqualTo("NOT_READY");
        assertThat(bloquee.checklist()).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("brouillons");
            assertThat(i.statut()).isEqualTo("BLOCKING");
        });
        ecritureComptableService.valider(entrepriseA.id(), brouillon.id(), principalA);
        assertThat(clotureReadinessService.evaluer(entrepriseA.id(), principalA).checklist()).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("brouillons");
            assertThat(i.statut()).isEqualTo("OK");
        });

        // 21. audit : creation, resolution automatique et ecart sont traces
        assertThat(evenements("FINDING_CREE")).isNotEmpty();
        assertThat(evenements("FINDING_RESOLU_AUTO")).isNotEmpty();
        assertThat(evenements("FINDING_REJETE")).isNotEmpty();

        // 22. traçabilite : aucune ecriture du moteur n'est orpheline de sa cause
        assertThat(jdbc.queryForObject("select count(*) from accounting_operation_proposal where source_type is null or source_id is null", Long.class)).isZero();
    }

    // =====================================================================================================
    // Refus d'imputation -> constat ACCOUNT_*, survit au rollback 422, se ferme quand la cause aboutit
    // =====================================================================================================

    @Test
    void un_refus_d_imputation_devient_un_constat_qui_survit_au_rollback_puis_se_ferme_au_succes() {
        CompteComptable immobilisations = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseA.id(), "244").orElseThrow();
        immobilisations.setActif(false);
        compteComptableRepository.save(immobilisations);
        UUID factureId = UUID.randomUUID();

        assertThat(statut(() -> facturer(factureId, "1000000", "180000"))).isEqualTo(422);

        List<Finding> refus = actifs(entrepriseA, ControlCodes.ACCOUNT_INVALID);
        assertThat(refus).singleElement().satisfies(f -> {
            assertThat(f.getSourceType()).isEqualTo("FACTURE_FOURNISSEUR");
            assertThat(f.getSourceId()).isEqualTo(factureId.toString());
            assertThat(f.getControlSeverity()).isEqualTo(ControlSeverity.ERROR);
            assertThat(f.getDescription()).contains("Camion");
        });
        assertThat(revueService.lister(entrepriseA.id(), new RevueComptableService.Filtre(RevueComptableService.Tuile.IMPUTATIONS, null, null, null,
                null, null, null, null, null), 0, 10, principalA).total()).isEqualTo(1);

        // rejeu du refus : le meme constat est mis a jour, jamais duplique
        assertThat(statut(() -> facturer(factureId, "1000000", "180000"))).isEqualTo(422);
        assertThat(actifs(entrepriseA, ControlCodes.ACCOUNT_INVALID)).hasSize(1);

        // la configuration est corrigee, la meme cause aboutit : le constat se ferme tout seul
        immobilisations.setActif(true);
        compteComptableRepository.save(immobilisations);
        facturer(factureId, "1000000", "180000");
        assertThat(actifs(entrepriseA, ControlCodes.ACCOUNT_INVALID)).isEmpty();
        assertThat(tous(entrepriseA, ControlCodes.ACCOUNT_INVALID)).singleElement()
                .satisfies(f -> assertThat(f.getResolutionType()).isEqualTo(TypeResolution.SYSTEM));
    }

    // =====================================================================================================
    // Jamais 1500 Findings pour 1500 operations
    // =====================================================================================================

    @Test
    void un_gros_releve_sans_correspondance_ne_produit_qu_un_constat() {
        StringBuilder csv = new StringBuilder("date;libelle;montant\n");
        for (int i = 1; i <= 120; i++) {
            csv.append(LocalDate.now()).append(";Operation ").append(i).append(";-").append(1000 + i).append('\n');
        }
        UUID compteBanque = compte(entrepriseA, "521");
        rapprochementService.importer(entrepriseA.id(), new ImporterReleveRequest(compteBanque, "Gros releve", LocalDate.now().minusDays(10),
                LocalDate.now(), BigDecimal.ZERO, BigDecimal.ZERO), new MockMultipartFile("fichier", "releve.csv", "text/csv",
                csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), principalA);

        controlRunService.executer(entreprise(entrepriseA));

        List<Finding> constats = actifs(entrepriseA, ControlCodes.UNMATCHED_PAYMENT);
        assertThat(constats).hasSize(1);
        assertThat(constats.get(0).getSourceType()).isEqualTo("RELEVE");
        assertThat(constats.get(0).getEvidenceJson().get("nbLignesNonRapprochees")).isEqualTo(120);
    }

    @Test
    void les_constats_de_l_entreprise_A_sont_invisibles_et_intouchables_depuis_B() {
        facturer(UUID.randomUUID(), "1000000", "180000");
        controlRunService.executer(entreprise(entrepriseA));
        List<Finding> chezA = actifs(entrepriseA, ControlCodes.MISSING_DOCUMENT);
        assertThat(chezA).isNotEmpty();

        assertThat(revueService.lister(entrepriseB.id(), new RevueComptableService.Filtre(null, null, null, null, null, null, null, null, null),
                0, 50, principalB).total()).isZero();
        assertThat(statut(() -> revueService.obtenir(entrepriseA.id(), chezA.get(0).getId(), principalB))).isEqualTo(404);
        assertThat(statut(() -> revueService.relancer(entrepriseA.id(), principalB))).isEqualTo(404);
        assertThat(statut(() -> findingService.rejeter(entrepriseA.id(), chezA.get(0).getId(), "motif", principalB))).isEqualTo(404);
    }

    // ----------------------------------------------------------------------------------------- utilitaires

    private List<Finding> actifs(EntrepriseDto entreprise, String code) {
        return findingRepository.findActifsParCodes(entreprise.id(), List.of(code));
    }

    private List<Finding> tous(EntrepriseDto entreprise, String code) {
        return findingRepository.findAll().stream()
                .filter(f -> f.getEntreprise().getId().equals(entreprise.id()) && f.getControlCode().equals(code)).toList();
    }

    private List<AuditEvent> evenements(String action) {
        return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).toList();
    }

    private Entreprise entreprise(EntrepriseDto dto) {
        return entrepriseRepository.findById(dto.id()).orElseThrow();
    }

    private EcritureComptableDto facturer(UUID factureId, String ht, String tva) {
        BigDecimal taux = new BigDecimal(tva).multiply(new BigDecimal("100")).divide(new BigDecimal(ht), 2, java.math.RoundingMode.HALF_UP);
        var proposition = engine.proposerAchatEtValiderImmediatement(entrepriseA.id(), new AchatMultiLigneOperationRequest(fournisseurA.id(),
                LocalDate.now(), "FF-" + factureId.toString().substring(0, 6), "Facture immobilisation", List.of(new LigneAchatOperationRequest(
                "Camion", NatureAchat.IMMOBILISATION, new BigDecimal(ht), taux, compte(entrepriseA, "244"), null)),
                null, AccountingSource.de("FACTURE_FOURNISSEUR", factureId, "VALIDATION")), principalA);
        return ecritureComptableService.obtenir(entrepriseA.id(), proposition.ecritureId(), principalA);
    }

    private ImmobilisationDto creerImmobilisation(String libelle, String valeur) {
        return immobilisationService.creer(entrepriseA.id(), new CreerImmobilisationRequest(compte(entrepriseA, "244"), compte(entrepriseA, "281"),
                libelle, TypeImmobilisation.CORPORELLE, LocalDate.now().withDayOfYear(1), new BigDecimal(valeur), 5, ModeAmortissement.LINEAIRE), principalA);
    }

    private ReleveBancaireDto importerReleve(String... montants) {
        StringBuilder csv = new StringBuilder("date;libelle;montant\n");
        int i = 1;
        for (String montant : montants) {
            csv.append(LocalDate.now()).append(";Virement ").append(i++).append(';').append(montant).append('\n');
        }
        BigDecimal soldeFin = java.util.Arrays.stream(montants).map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
        return rapprochementService.importer(entrepriseA.id(), new ImporterReleveRequest(compte(entrepriseA, "521"), "Releve du mois",
                LocalDate.now().minusDays(10), LocalDate.now(), BigDecimal.ZERO, soldeFin),
                new MockMultipartFile("fichier", "releve.csv", "text/csv", csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), principalA);
    }

    /** Reglement fournisseur : debit 401 (fournisseur) / credit 521, valide (donc visible du rapprochement). */
    private void reglerFournisseur(String montant) {
        UUID od = journalRepository.findByEntrepriseIdAndCode(entrepriseA.id(), "OD").orElseThrow().getId();
        EcritureComptableDto ecriture = ecritureComptableService.creerManuelle(entrepriseA.id(), new CreerEcritureRequest(od, LocalDate.now(),
                "Reglement fournisseur", null, List.of(
                new LigneEcritureRequest(fournisseurA.compteComptableId(), "Reglement", new BigDecimal(montant), BigDecimal.ZERO, fournisseurA.id()),
                new LigneEcritureRequest(compte(entrepriseA, "521"), "Banque", BigDecimal.ZERO, new BigDecimal(montant), null))), principalA);
        ecritureComptableService.valider(entrepriseA.id(), ecriture.id(), principalA);
    }

    private EcritureComptableDto saisirBrouillon() {
        UUID od = journalRepository.findByEntrepriseIdAndCode(entrepriseA.id(), "OD").orElseThrow().getId();
        return ecritureComptableService.creerManuelle(entrepriseA.id(), new CreerEcritureRequest(od, LocalDate.now(), "Brouillon", null, List.of(
                new LigneEcritureRequest(compte(entrepriseA, "601"), "d", new BigDecimal("1000"), BigDecimal.ZERO, null),
                new LigneEcritureRequest(compte(entrepriseA, "521"), "c", BigDecimal.ZERO, new BigDecimal("1000"), null))), principalA);
    }

    private long nombreEcritures(EntrepriseDto entreprise) {
        return ecritureComptableRepository.findAll().stream().filter(e -> e.getEntreprise().getId().equals(entreprise.id())).count();
    }

    private UUID compte(EntrepriseDto entreprise, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), numero).orElseThrow().getId();
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
        return entrepriseService.creer(new CreerEntrepriseRequest("PME Pilotee " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), principal);
    }

    private UtilisateurPrincipal creerPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Pilote");
        tenant.setSlug("cabinet-pilote-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("pilote-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Pilote");
        utilisateur.setRoles(Set.of(role));
        return new UtilisateurPrincipal(utilisateurRepository.save(utilisateur));
    }
}
