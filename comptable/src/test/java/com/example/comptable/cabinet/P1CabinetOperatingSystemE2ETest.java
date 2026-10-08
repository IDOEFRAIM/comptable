package com.example.comptable.cabinet;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.cabinet.web.dto.CabinetCockpitDto;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneFacture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.copilot.CabinetCopilotService;
import com.example.comptable.copilot.CopilotService;
import com.example.comptable.copilot.dto.CopilotExplainResponseDTO;
import com.example.comptable.copilot.web.dto.CopilotResponseDto;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.finance.CashForecastService;
import com.example.comptable.finance.CashPositionService;
import com.example.comptable.finance.web.dto.CashPositionDto;
import com.example.comptable.finance.web.dto.PrevisionTresorerieDto;
import com.example.comptable.fiscalite.conformite.ComplianceCenterService;
import com.example.comptable.fiscalite.conformite.web.dto.ComplianceCenterDto;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.recouvrement.CollectionRiskService;
import com.example.comptable.recouvrement.web.dto.CollectionRiskDto;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Bout en bout mission P1 (section 53) : cinq scenarios réels contre Postgres réel, seul
 * {@link AiServiceClient} mocké (uniquement pour {@code copilotExplain} - la mise en mots, jamais
 * les faits). Deux entreprises réelles dans le même cabinet, l'une saine, l'autre à risque, pour
 * prouver que l'agrégation cabinet-wide (section 2) fonctionne sur un portefeuille réel, pas un
 * mock empilé.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class P1CabinetOperatingSystemE2ETest {

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UtilisateurRepository utilisateurRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntrepriseService entrepriseService;
    @Autowired
    private EntrepriseRepository entrepriseRepository;
    @Autowired
    private CompteComptableRepository compteComptableRepository;
    @Autowired
    private JournalRepository journalRepository;
    @Autowired
    private ExerciceComptableRepository exerciceComptableRepository;
    @Autowired
    private EcritureComptableRepository ecritureComptableRepository;
    @Autowired
    private TiersRepository tiersRepository;
    @Autowired
    private FactureRepository factureRepository;
    @Autowired
    private DeclarationFiscaleRepository declarationFiscaleRepository;
    @Autowired
    private FindingRepository findingRepository;

    @Autowired
    private CabinetCockpitService cabinetCockpitService;
    @Autowired
    private CabinetCopilotService cabinetCopilotService;
    @Autowired
    private CopilotService copilotService;
    @Autowired
    private CashPositionService cashPositionService;
    @Autowired
    private CashForecastService cashForecastService;
    @Autowired
    private CollectionRiskService collectionRiskService;
    @Autowired
    private ComplianceCenterService complianceCenterService;

    @MockitoBean
    private AiServiceClient aiServiceClient;

    private UtilisateurPrincipal principal;
    private UUID entrepriseSaineId;
    private UUID entrepriseRisqueId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet P1 E2E");
        tenant.setSlug("cabinet-p1-e2e-" + UUID.randomUUID());
        tenant.setPays("BF");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("p1-e2e-" + UUID.randomUUID() + "@test.bf");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Kabore");
        utilisateur.setPrenom("Salamata");
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);
        principal = new UtilisateurPrincipal(utilisateur);

        EntrepriseDto saine = entrepriseService.creer(
                new CreerEntrepriseRequest("SARL Client Sain", null, null, null, null, null, "BF", "XOF"), principal);
        EntrepriseDto risque = entrepriseService.creer(
                new CreerEntrepriseRequest("SARL Client Risque", null, null, null, null, null, "BF", "XOF"), principal);
        entrepriseSaineId = saine.id();
        entrepriseRisqueId = risque.id();
    }

    private Finding signalerFindingCritique(UUID entrepriseId, String titre) {
        Finding f = new Finding();
        f.setEntreprise(entrepriseRepository.getReferenceById(entrepriseId));
        // Code hors perimetre des detecteurs : le constat n'est pas ferme automatiquement par un passage de controles
        // (un constat dont la cause "disparait" est resolu par le systeme, ce que ce test n'exerce pas).
        f.setType(TypeFinding.CLOSING_BLOCKER);
        f.setSeverity(SeveriteFinding.CRITICAL);
        f.setControlCode("CLOSING_BLOCKER");
        f.setCategory(com.example.comptable.controle.ControlCategory.CLOSING);
        f.setControlSeverity(com.example.comptable.controle.ControlSeverity.BLOCKING);
        f.setLastDetectedAt(java.time.OffsetDateTime.now());
        f.setSourceType("DECLARATION_FISCALE");
        f.setSourceId("TVA-" + UUID.randomUUID());
        f.setTitle(titre);
        f.setDescription("Declaration en retard depuis plus de 30 jours.");
        f.setRecommendedAction("Deposer et payer la declaration sans delai.");
        f.setStatus(StatutFinding.OPEN);
        return findingRepository.save(f);
    }

    // --- E2E 1 : Cabinet Cockpit -------------------------------------------------------------

    @Test
    void e2e_cabinet_cockpit_agrege_un_portefeuille_reel_de_deux_clients() {
        signalerFindingCritique(entrepriseRisqueId, "Declaration TVA en retard");

        CabinetCockpitDto cockpit = cabinetCockpitService.generer(principal);

        assertThat(cockpit.totalClients()).isEqualTo(2);
        assertThat(cockpit.clientsNormaux()).isEqualTo(1);
        assertThat(cockpit.clientsBloques()).isEqualTo(1);
        assertThat(cockpit.clients()).extracting("raisonSociale")
                .contains("SARL Client Sain", "SARL Client Risque");
        assertThat(cockpit.topRisques()).isNotEmpty();
        assertThat(cockpit.topRisques().get(0).raisonSociale()).isEqualTo("SARL Client Risque");
        assertThat(cockpit.topRisques().get(0).cause()).isEqualTo("Declaration TVA en retard");
    }

    // --- E2E 2 : Copilot cabinet ---------------------------------------------------------------

    @Test
    void e2e_copilot_cabinet_repond_avec_des_preuves_reelles() {
        signalerFindingCritique(entrepriseRisqueId, "Declaration TVA en retard");
        when(aiServiceClient.copilotExplain(any())).thenReturn(Optional.of(
                new CopilotExplainResponseDTO("Un client du cabinet est bloque a cause d'une declaration TVA en retard.")));

        CopilotResponseDto reponse = cabinetCopilotService.repondre("Quels clients sont bloqués ?", principal);

        assertThat(reponse.answer()).contains("bloque");
        assertThat(reponse.evidence()).isNotEmpty();
        assertThat(reponse.evidence().get(0).sourceType()).isEqualTo("ENTREPRISE");
    }

    // --- E2E 3 : Copilot comptable ---------------------------------------------------------------

    @Test
    void e2e_copilot_comptable_explique_pourquoi_le_dossier_nest_pas_pret() {
        signalerFindingCritique(entrepriseRisqueId, "Declaration TVA en retard");
        when(aiServiceClient.copilotExplain(any())).thenReturn(Optional.of(
                new CopilotExplainResponseDTO("Le dossier est bloque a cause d'une declaration TVA en retard depuis plus de 30 jours.")));

        CopilotResponseDto reponse = copilotService.repondre(
                entrepriseRisqueId, "Pourquoi ce dossier n'est-il pas pret ?", principal);

        assertThat(reponse.answer()).contains("declaration TVA");
        assertThat(reponse.evidence()).isNotEmpty();
        assertThat(reponse.confidence()).isGreaterThan(0);
    }

    // --- E2E 4 : Tresorerie (Cash Position + Cash Forecast) -----------------------------------

    @Test
    void e2e_cash_position_et_forecast() {
        CompteComptable compteBanque = compteComptableRepository
                .findByEntrepriseIdAndNumero(entrepriseSaineId, "521").orElseThrow();
        CompteComptable compteCharge = compteComptableRepository
                .findByEntrepriseIdAndNumero(entrepriseSaineId, "601").orElseThrow();
        CompteComptable compte411 = compteComptableRepository
                .findByEntrepriseIdAndNumero(entrepriseSaineId, "411").orElseThrow();

        Journal journalOd = journalRepository.findByEntrepriseIdAndCode(entrepriseSaineId, "OD").orElseThrow();
        LocalDate aujourdHui = LocalDate.now();
        ExerciceComptable exercice = exerciceComptableRepository
                .findByEntrepriseIdAndDate(entrepriseSaineId, aujourdHui).orElseThrow();

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(entrepriseRepository.getReferenceById(entrepriseSaineId));
        ecriture.setExercice(exercice);
        ecriture.setJournal(journalOd);
        ecriture.setNumeroPiece("OD-E2E-0001");
        ecriture.setDateEcriture(aujourdHui);
        ecriture.setLibelle("Encaissement E2E");
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.ajouterLigne(LigneEcriture.debit(compteBanque, new BigDecimal("8200000"), "Encaissement"));
        ecriture.ajouterLigne(LigneEcriture.credit(compteCharge, new BigDecimal("8200000"), "Encaissement"));
        ecritureComptableRepository.save(ecriture);

        CashPositionDto position = cashPositionService.generer(entrepriseSaineId, aujourdHui, principal);
        assertThat(position.total()).isEqualByComparingTo("8200000");
        assertThat(position.parCompte()).anySatisfy(p -> assertThat(p.compteNumero()).isEqualTo("521"));

        Tiers clientTiers = new Tiers();
        clientTiers.setEntreprise(entrepriseRepository.getReferenceById(entrepriseSaineId));
        clientTiers.setType(TypeTiers.CLIENT);
        clientTiers.setNom("Client Alpha");
        clientTiers.setCompteComptable(compte411);
        clientTiers = tiersRepository.save(clientTiers);

        Facture factureConnue = nouvelleFacture(entrepriseSaineId, clientTiers, "FA-E2E-0001",
                aujourdHui.minusDays(10), aujourdHui.plusDays(5), new BigDecimal("1000000"));
        Facture factureEnRetard = nouvelleFacture(entrepriseSaineId, clientTiers, "FA-E2E-0002",
                aujourdHui.minusDays(40), aujourdHui.minusDays(15), new BigDecimal("500000"));
        factureRepository.save(factureConnue);
        factureRepository.save(factureEnRetard);

        List<PrevisionTresorerieDto> previsions = cashForecastService.generer(entrepriseSaineId, principal);
        assertThat(previsions).hasSize(3);
        PrevisionTresorerieDto horizon30 = previsions.stream().filter(p -> p.horizonJours() == 30).findFirst().orElseThrow();
        assertThat(horizon30.encaissementsConnus()).isEqualByComparingTo("1000000");
        // retard de 15j (<= 30j) -> probabilite 0.90 -> 500000 * 0.90 = 450000
        assertThat(horizon30.encaissementsAttendus()).isEqualByComparingTo("450000");
    }

    private Facture nouvelleFacture(UUID entrepriseId, Tiers tiers, String numero, LocalDate emission,
                                     LocalDate echeance, BigDecimal montant) {
        Facture facture = new Facture();
        facture.setEntreprise(entrepriseRepository.getReferenceById(entrepriseId));
        facture.setTiers(tiers);
        facture.setType(TypeFacture.FACTURE);
        facture.setNumero(numero);
        facture.setDateEmission(emission);
        facture.setDateEcheance(echeance);
        facture.setStatut(StatutFacture.VALIDEE);
        LigneFacture ligne = new LigneFacture();
        ligne.setFacture(facture);
        ligne.setDesignation("Prestation E2E");
        ligne.setQuantite(BigDecimal.ONE);
        ligne.setPrixUnitaire(montant);
        ligne.setTauxTva(BigDecimal.ZERO);
        ligne.setNumeroLigne(1);
        facture.setLignes(List.of(ligne));
        return facture;
    }

    // --- E2E 5 : Recouvrement (Collection Risk) -----------------------------------------------

    @Test
    void e2e_collection_risk_classe_un_client_en_retard_important() {
        CompteComptable compte411 = compteComptableRepository
                .findByEntrepriseIdAndNumero(entrepriseSaineId, "411").orElseThrow();
        CompteComptable compteVente = compteComptableRepository
                .findByEntrepriseIdAndNumero(entrepriseSaineId, "701").orElseThrow();
        Journal journalVentes = journalRepository.findByEntrepriseIdAndCode(entrepriseSaineId, "VTE").orElseThrow();

        Tiers clientEnRetard = new Tiers();
        clientEnRetard.setEntreprise(entrepriseRepository.getReferenceById(entrepriseSaineId));
        clientEnRetard.setType(TypeTiers.CLIENT);
        clientEnRetard.setNom("Client Tres En Retard");
        clientEnRetard.setCompteComptable(compte411);
        clientEnRetard = tiersRepository.save(clientEnRetard);

        LocalDate ilYa70Jours = LocalDate.now().minusDays(70);
        ExerciceComptable exercice = exerciceComptableRepository
                .findByEntrepriseIdAndDate(entrepriseSaineId, ilYa70Jours).orElseThrow();

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(entrepriseRepository.getReferenceById(entrepriseSaineId));
        ecriture.setExercice(exercice);
        ecriture.setJournal(journalVentes);
        ecriture.setNumeroPiece("VTE-E2E-0001");
        ecriture.setDateEcriture(ilYa70Jours);
        ecriture.setLibelle("Vente E2E");
        ecriture.setStatut(StatutEcriture.VALIDEE);
        LigneEcriture ligneClient = LigneEcriture.debit(compte411, new BigDecimal("650000"), "Vente E2E");
        ligneClient.setTiers(clientEnRetard);
        ecriture.ajouterLigne(ligneClient);
        ecriture.ajouterLigne(LigneEcriture.credit(compteVente, new BigDecimal("650000"), "Vente E2E"));
        ecritureComptableRepository.save(ecriture);

        List<CollectionRiskDto> risques = collectionRiskService.generer(entrepriseSaineId, principal);

        assertThat(risques).anySatisfy(r -> {
            assertThat(r.tiersNom()).isEqualTo("Client Tres En Retard");
            assertThat(r.niveauRisque()).isEqualTo("HIGH");
            assertThat(r.actionRecommandee()).containsIgnoringCase("contact humain");
        });
    }

    // --- E2E 6 : Compliance Center --------------------------------------------------------------

    @Test
    void e2e_compliance_center_liste_les_obligations_et_les_anomalies() {
        DeclarationFiscale declaration = new DeclarationFiscale();
        declaration.setEntreprise(entrepriseRepository.getReferenceById(entrepriseSaineId));
        declaration.setTypeImpot(TypeImpot.TVA);
        declaration.setPeriode("2026-07");
        declaration.setDateEcheance(LocalDate.now().plusDays(10));
        declaration.setStatut(StatutDeclaration.A_FAIRE);
        declaration.setMontant(new BigDecimal("450000"));
        declarationFiscaleRepository.save(declaration);

        signalerFindingCritiqueSurEntreprise(entrepriseSaineId, "Anomalie TVA detectee");

        ComplianceCenterDto conformite = complianceCenterService.genererCentreConformite(entrepriseSaineId, principal);

        assertThat(conformite.obligations()).anySatisfy(o -> assertThat(o.periode()).isEqualTo("2026-07"));
        assertThat(conformite.anomalies()).anySatisfy(a -> assertThat(a.title()).isEqualTo("Anomalie TVA detectee"));
    }

    private void signalerFindingCritiqueSurEntreprise(UUID entrepriseId, String titre) {
        Finding f = new Finding();
        f.setEntreprise(entrepriseRepository.getReferenceById(entrepriseId));
        f.setType(TypeFinding.TAX_REVIEW);
        f.setSeverity(SeveriteFinding.IMPORTANT);
        f.setControlCode("TAX_DECLARATION_MISSING");
        f.setCategory(com.example.comptable.controle.ControlCategory.TAX);
        f.setControlSeverity(com.example.comptable.controle.ControlSeverity.WARNING);
        f.setLastDetectedAt(java.time.OffsetDateTime.now());
        f.setSourceType("DECLARATION_FISCALE");
        f.setSourceId("TVA-" + UUID.randomUUID());
        f.setTitle(titre);
        f.setStatus(StatutFinding.OPEN);
        findingRepository.save(f);
    }
}
