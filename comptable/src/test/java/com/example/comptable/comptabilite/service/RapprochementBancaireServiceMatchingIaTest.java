package com.example.comptable.comptabilite.service;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.agent.finding.MissingEvidenceAnalyzer;
import com.example.comptable.agent.finding.WhatsAppResolutionService;
import com.example.comptable.agent.matching.MatchDecisionEngine;
import com.example.comptable.agent.matching.MatchingContextBuilder;
import com.example.comptable.agent.matching.ResultatDecision;
import com.example.comptable.agent.matching.domain.AiMatchProposal;
import com.example.comptable.agent.matching.domain.DecisionRapprochementIA;
import com.example.comptable.agent.matching.dto.MatchingContextDTO;
import com.example.comptable.agent.matching.dto.MatchingRequestDTO;
import com.example.comptable.agent.matching.dto.MatchingResponseDTO;
import com.example.comptable.agent.matching.dto.MatchingSourceDTO;
import com.example.comptable.agent.rapprochement.MatchingSemantiqueService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.ReleveBancaire;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.repository.ReleveBancaireRepository;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.comptabilite.web.dto.ReleveBancaireDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifie le branchement reel du Universal Matching Engine dans
 * {@code RapprochementBancaireService.rapprocherAutomatiquement} (mission 2, section 6) quand
 * {@code agent.matching-ia.enabled=true}. Complementaire de {@link RapprochementBancaireServiceTest},
 * qui verifie que ce filet reste un no-op quand le flag est absent/false (comportement par
 * defaut). Construction explicite du service (pas {@code @InjectMocks}) car {@code matchingIaActif}
 * est un booleen aliment par {@code @Value}, pas un type mockable.
 */
@ExtendWith(MockitoExtension.class)
class RapprochementBancaireServiceMatchingIaTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private ReleveBancaireRepository releveBancaireRepository;
    @Mock
    private LigneReleveRepository ligneReleveRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private MatchingSemantiqueService matchingSemantiqueService;
    @Mock
    private MatchingContextBuilder matchingContextBuilder;
    @Mock
    private MatchDecisionEngine matchDecisionEngine;
    @Mock
    private AiServiceClient aiServiceClient;
    @Mock
    private MissingEvidenceAnalyzer missingEvidenceAnalyzer;
    @Mock
    private WhatsAppResolutionService whatsAppResolutionService;

    private RapprochementBancaireService service;
    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private CompteComptable compteBanque;

    @BeforeEach
    void setUp() {
        service = new RapprochementBancaireService(entrepriseAccessService, compteComptableRepository,
                releveBancaireRepository, ligneReleveRepository, ligneEcritureRepository, matchingSemantiqueService,
                matchingContextBuilder, matchDecisionEngine, aiServiceClient, missingEvidenceAnalyzer,
                whatsAppResolutionService, new SimpleMeterRegistry(), true);

        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        compteBanque = new CompteComptable();
        compteBanque.setId(UUID.randomUUID());
        compteBanque.setEntreprise(entreprise);
        compteBanque.setNumero("521");
        compteBanque.setIntitule("Banques");

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private ImporterReleveRequest requete() {
        return new ImporterReleveRequest(compteBanque.getId(), "Releve janvier",
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31),
                new BigDecimal("10000"), new BigDecimal("40000"));
    }

    private void stubberEtapesPrecedentes() {
        when(compteComptableRepository.findById(compteBanque.getId())).thenReturn(Optional.of(compteBanque));
        when(releveBancaireRepository.save(any())).thenAnswer(inv -> {
            ReleveBancaire r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(ligneEcritureRepository.rechercherMouvementsValides(any(), any(), any())).thenReturn(List.of());
        when(ligneReleveRepository.rechercherLigneEcritureIdsPointees(compteBanque.getId())).thenReturn(List.of());
        when(ligneEcritureRepository.findAllById(any())).thenReturn(List.of());
        when(ligneEcritureRepository.soldeNetAvant(any(), any())).thenReturn(BigDecimal.ZERO);
        when(matchingSemantiqueService.proposerAppariements(any(), any())).thenReturn(List.of());
    }

    @Test
    void applique_le_match_auto_propose_par_le_universal_matching_engine_quand_actif() {
        MockMultipartFile fichier = new MockMultipartFile("fichier", "releve.csv", "text/csv",
                "date;libelle;montant\n2027-01-05;VIR ABC SARL FAC452;15000\n".getBytes(StandardCharsets.UTF_8));
        UUID ecritureAutoMatchee = UUID.randomUUID();
        stubberEtapesPrecedentes();

        MatchingRequestDTO requeteIa = new MatchingRequestDTO(
                new MatchingSourceDTO("BANK_TRANSACTION", "x", LocalDate.of(2027, 1, 5), new BigDecimal("15000"),
                        "XOF", "VIR ABC SARL FAC452", null, null),
                List.of(), new MatchingContextDTO(null, null, null, List.of()));
        when(matchingContextBuilder.construire(any(), any(), any(), any())).thenReturn(requeteIa);

        MatchingResponseDTO reponseIa = new MatchingResponseDTO("MATCH_PROPOSED", 0.97, "ONE_TO_ONE", List.of(),
                List.of(), List.of(), false);
        when(aiServiceClient.analyzeMatching(requeteIa)).thenReturn(Optional.of(reponseIa));

        AiMatchProposal proposition = new AiMatchProposal();
        when(matchDecisionEngine.statuer(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            LigneReleve ligne = inv.getArgument(1);
            ligne.setLigneEcritureId(ecritureAutoMatchee);
            return new ResultatDecision(DecisionRapprochementIA.MATCH_AUTO, proposition, ecritureAutoMatchee);
        });

        ReleveBancaireDto resultat = service.importer(entrepriseId, requete(), fichier, principal);

        assertThat(resultat.lignes()).hasSize(1);
        assertThat(resultat.lignes().get(0).pointee()).isTrue();
        assertThat(resultat.lignes().get(0).ligneEcritureId()).isEqualTo(ecritureAutoMatchee);
    }

    @Test
    void laisse_la_ligne_non_rapprochee_quand_lia_est_indisponible() {
        MockMultipartFile fichier = new MockMultipartFile("fichier", "releve.csv", "text/csv",
                "date;libelle;montant\n2027-01-05;Virement inconnu;15000\n".getBytes(StandardCharsets.UTF_8));
        stubberEtapesPrecedentes();

        MatchingRequestDTO requeteIa = new MatchingRequestDTO(
                new MatchingSourceDTO("BANK_TRANSACTION", "x", LocalDate.of(2027, 1, 5), new BigDecimal("15000"),
                        "XOF", "Virement inconnu", null, null),
                List.of(), new MatchingContextDTO(null, null, null, List.of()));
        when(matchingContextBuilder.construire(any(), any(), any(), any())).thenReturn(requeteIa);
        when(aiServiceClient.analyzeMatching(requeteIa)).thenReturn(Optional.empty());

        AiMatchProposal proposition = new AiMatchProposal();
        when(matchDecisionEngine.statuer(any(), any(), any(), any(), any()))
                .thenReturn(new ResultatDecision(DecisionRapprochementIA.AI_UNAVAILABLE, proposition, null));

        ReleveBancaireDto resultat = service.importer(entrepriseId, requete(), fichier, principal);

        assertThat(resultat.lignes()).hasSize(1);
        assertThat(resultat.lignes().get(0).pointee()).isFalse();
    }
}
