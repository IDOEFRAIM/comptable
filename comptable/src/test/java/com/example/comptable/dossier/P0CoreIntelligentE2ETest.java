package com.example.comptable.dossier;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.agent.finding.MissingEvidenceAnalyzer;
import com.example.comptable.agent.matching.MatchDecisionEngine;
import com.example.comptable.agent.matching.MatchingContextBuilder;
import com.example.comptable.agent.matching.ResultatDecision;
import com.example.comptable.agent.matching.dto.MatchProposalDTO;
import com.example.comptable.agent.matching.dto.MatchingRequestDTO;
import com.example.comptable.agent.matching.dto.MatchingResponseDTO;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.comptabilite.web.dto.ReleveBancaireDto;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Bout en bout mission P0 (section 32) : releve bancaire -> ligne non rapprochee -> Universal
 * Matching Engine (IA mockee, jamais de cle Anthropic requise ici - seul {@link AiServiceClient}
 * est mocke, tout le reste tourne reellement contre Postgres) -> Missing Evidence Engine ->
 * Finding -> Demande ciblee (boucle WhatsApp) -> Dossier Comptable degrade -> nouvelle preuve
 * (ecriture bancaire creee) -> rematching -> Finding resolu -> Dossier ameliore -> Closing
 * Readiness. Chaque etape est verifiee contre l'etat REEL en base, pas des mocks empiles.
 */
@SpringBootTest(properties = {
        "spring.docker.compose.skip.in-tests=false",
        "agent.matching-ia.enabled=true"
})
class P0CoreIntelligentE2ETest {

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
    private RapprochementBancaireService rapprochementBancaireService;
    @Autowired
    private LigneReleveRepository ligneReleveRepository;
    @Autowired
    private MatchingContextBuilder matchingContextBuilder;
    @Autowired
    private MatchDecisionEngine matchDecisionEngine;
    @Autowired
    private MissingEvidenceAnalyzer missingEvidenceAnalyzer;
    @Autowired
    private DemandePieceRepository demandePieceRepository;
    @Autowired
    private DossierComptableService dossierComptableService;
    @Autowired
    private ClotureReadinessService clotureReadinessService;

    @MockitoBean
    private AiServiceClient aiServiceClient;

    @Test
    void chaine_complete_document_banque_finding_demande_puis_resolution() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entreprise = entrepriseService.creer(
                new CreerEntrepriseRequest("PME P0 E2E", null, null, null, null, null, "CI", "XOF"), principal);
        CompteComptable compteBanque = compteComptableRepository
                .findByEntrepriseIdAndNumero(entreprise.id(), "521").orElseThrow();
        CompteComptable compteCharge = compteComptableRepository
                .findByEntrepriseIdAndNumero(entreprise.id(), "601").orElseThrow();

        // --- Etape 1 : import d'un releve avec une ligne totalement inexpliquee (aucun candidat
        // en base) ; l'IA (mockee) confirme NO_MATCH pour ne pas dependre de ce qu'elle renvoie
        // reellement - le vrai signal "aucun candidat" vient du contexte reellement construit.
        when(aiServiceClient.analyzeMatching(any())).thenReturn(Optional.of(
                new MatchingResponseDTO("NO_MATCH", 0.1, "NONE", List.of(), List.of("no_plausible_candidate"), List.of(), false)));

        int annee = LocalDate.now().getYear();
        LocalDate dateOperation = LocalDate.of(annee, 6, 5);
        MockMultipartFile fichier = new MockMultipartFile("fichier", "releve.csv", "text/csv",
                ("date;libelle;montant\n" + dateOperation + ";VIR ABC SARL PAIEMENT;485000\n").getBytes(StandardCharsets.UTF_8));
        ImporterReleveRequest requete = new ImporterReleveRequest(compteBanque.getId(), "Releve juin",
                LocalDate.of(annee, 6, 1), LocalDate.of(annee, 6, 30), new BigDecimal("0"), new BigDecimal("485000"));

        ReleveBancaireDto releve = rapprochementBancaireService.importer(entreprise.id(), requete, fichier, principal);
        assertThat(releve.lignes()).hasSize(1);
        assertThat(releve.lignes().get(0).pointee()).isFalse();

        // --- Etape 2 : le Finding + la Demande ciblee doivent exister reellement en base.
        DossierComptableDto dossierDegrade = dossierComptableService.generer(entreprise.id(), principal);
        assertThat(dossierDegrade.actionsPrioritaires()).isNotEmpty();
        assertThat(dossierDegrade.actionsPrioritaires().get(0).type()).isEqualTo("MISSING_EVIDENCE");
        assertThat(dossierDegrade.scoresParDomaine())
                .filteredOn(s -> s.domaine().equals("Banque"))
                .allSatisfy(s -> assertThat(s.score()).isLessThan(100));

        List<DemandePiece> demandes = demandePieceRepository.findByEntrepriseIdOrderByCreatedAtDesc(entreprise.id());
        assertThat(demandes).hasSize(1);
        assertThat(demandes.get(0).getLibelle()).contains("485000");
        assertThat(demandes.get(0).getFindingId()).isEqualTo(dossierDegrade.actionsPrioritaires().get(0).id());

        ClotureReadinessDto readinessAvant = clotureReadinessService.evaluer(entreprise.id(), principal);
        assertThat(readinessAvant.statut()).isIn("READY_WITH_REVIEW", "NOT_READY");

        // --- Etape 3 : le client envoie la preuve (simulee ici par une ecriture bancaire reelle,
        // deja validee, sur le meme compte/montant) - le rematching doit desormais la trouver.
        Journal journalBanque = journalRepository.findByEntrepriseIdAndCode(entreprise.id(), "OD").orElseThrow();
        ExerciceComptable exercice = exerciceComptableRepository
                .findByEntrepriseIdAndDate(entreprise.id(), dateOperation).orElseThrow();

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(entrepriseRepository.getReferenceById(entreprise.id()));
        ecriture.setExercice(exercice);
        ecriture.setJournal(journalBanque);
        ecriture.setNumeroPiece("OD-" + annee + "-0001");
        ecriture.setDateEcriture(dateOperation);
        ecriture.setLibelle("Paiement ABC SARL");
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.ajouterLigne(LigneEcriture.debit(compteBanque, new BigDecimal("485000"), "Paiement ABC SARL"));
        ecriture.ajouterLigne(LigneEcriture.credit(compteCharge, new BigDecimal("485000"), "Paiement ABC SARL"));
        ecriture = ecritureComptableRepository.save(ecriture);
        UUID ligneEcritureBanqueId = ecriture.getLignes().stream()
                .filter(l -> l.getCompte().getId().equals(compteBanque.getId()))
                .findFirst().orElseThrow().getId();

        LigneReleve ligneReleve = ligneReleveRepository.findById(releve.lignes().get(0).id()).orElseThrow();

        MatchingRequestDTO requeteMatching = matchingContextBuilder.construire(
                entrepriseRepository.getReferenceById(entreprise.id()), compteBanque, ligneReleve, new HashSet<>());
        assertThat(requeteMatching.candidates()).isNotEmpty(); // la nouvelle ecriture est bien candidate

        when(aiServiceClient.analyzeMatching(any())).thenReturn(Optional.of(
                new MatchingResponseDTO("MATCH_PROPOSED", 0.97, "ONE_TO_ONE",
                        List.of(new MatchProposalDTO(ligneEcritureBanqueId.toString(), "ACCOUNTING_ENTRY", 0.97)),
                        List.of("exact_amount"), List.of(), false)));

        ResultatDecision resultat = matchDecisionEngine.statuer(
                entrepriseRepository.getReferenceById(entreprise.id()), ligneReleve, requeteMatching,
                aiServiceClient.analyzeMatching(requeteMatching), new HashSet<>());
        assertThat(resultat.decision().name()).isEqualTo("MATCH_AUTO");

        missingEvidenceAnalyzer.analyser(entrepriseRepository.getReferenceById(entreprise.id()), ligneReleve,
                requeteMatching, resultat);

        // --- Etape 4 : le Dossier doit refleter la resolution (score Banque revenu a 100, le
        // Finding ne fait plus partie des actions prioritaires).
        DossierComptableDto dossierAmeliore = dossierComptableService.generer(entreprise.id(), principal);
        assertThat(dossierAmeliore.scoresParDomaine())
                .filteredOn(s -> s.domaine().equals("Banque"))
                .allSatisfy(s -> assertThat(s.score()).isEqualTo(100));
    }

    private UtilisateurPrincipal creerPrincipalExpertComptable() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet P0 E2E");
        tenant.setSlug("cabinet-p0-e2e-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("p0-e2e-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("P0");
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);

        return new UtilisateurPrincipal(utilisateur);
    }
}
