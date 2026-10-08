package com.example.comptable.copilot;

import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.finding.web.dto.FindingDto;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.copilot.web.dto.CopilotResponseDto;
import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.DossierComptableService;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
import com.example.comptable.dossier.web.dto.ScoreDomaineDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.CashPositionService;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private DossierComptableService dossierComptableService;
    @Mock
    private ClotureReadinessService clotureReadinessService;
    @Mock
    private CashPositionService cashPositionService;
    @Mock
    private FindingRepository findingRepository;
    @Mock
    private FactureRepository factureRepository;
    @Mock
    private DeclarationFiscaleRepository declarationFiscaleRepository;
    @Mock
    private CopilotExplainer copilotExplainer;
    @Mock
    private CopilotTelemetryRecorder telemetryRecorder;

    @Mock
    private com.example.comptable.comptabilite.allocation.StatutPaiementFactureService statutPaiementFactureService;

    @InjectMocks
    private CopilotService copilotService;

    private UUID entrepriseId;
    private UtilisateurPrincipal principal;

    @BeforeEach
    void setUp() {
        lenient().when(statutPaiementFactureService.ouvertes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(inv -> ((java.util.Collection<com.example.comptable.comptabilite.domain.Facture>) inv.getArgument(1)).stream()
                        .map(f -> new com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte(f, f.montantTtc(), java.math.BigDecimal.ZERO, f.montantTtc(),
                                com.example.comptable.comptabilite.allocation.StatutPaiement.NON_PAYEE)).toList());
        entrepriseId = UUID.randomUUID();
        Entreprise entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("expert@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Ilboudo");
        utilisateur.setPrenom("Marie");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    @Test
    void repond_avec_evidence_pour_pourquoi_dossier_pas_pret() {
        DossierComptableDto dossier = new DossierComptableDto("SARL Test", 82, "A_SURVEILLER",
                List.of(new ScoreDomaineDto("Banque", 75)),
                List.of(finding("MISSING_EVIDENCE", "LIGNE_RELEVE", "ligne-1")), List.of());
        when(dossierComptableService.generer(entrepriseId, principal)).thenReturn(dossier);
        when(copilotExplainer.expliquer(any(), any(), any()))
                .thenReturn("Le dossier est a 82% a cause d'un justificatif manquant.");

        CopilotResponseDto reponse = copilotService.repondre(entrepriseId, "Pourquoi ce dossier n'est-il pas pret ?", principal);

        assertThat(reponse.answer()).isEqualTo("Le dossier est a 82% a cause d'un justificatif manquant.");
        assertThat(reponse.evidence()).hasSize(1);
        assertThat(reponse.evidence().get(0).sourceType()).isEqualTo("LIGNE_RELEVE");
        assertThat(reponse.confidence()).isGreaterThan(0);
    }

    @Test
    void question_hors_perimetre_ne_declenche_aucun_appel_de_donnees_ni_llm() {
        CopilotResponseDto reponse = copilotService.repondre(entrepriseId, "Quel temps fait-il ?", principal);

        assertThat(reponse.confidence()).isZero();
        assertThat(reponse.evidence()).isEmpty();
        verifyNoInteractions(dossierComptableService, clotureReadinessService, cashPositionService,
                findingRepository, factureRepository, declarationFiscaleRepository, copilotExplainer);
    }

    @Test
    void transmet_telle_quelle_lexplication_produite_par_le_copilot_explainer() {
        DossierComptableDto dossier = new DossierComptableDto("SARL Test", 100, "PRET", List.of(), List.of(), List.of());
        when(dossierComptableService.generer(entrepriseId, principal)).thenReturn(dossier);
        when(copilotExplainer.expliquer(any(), any(), any()))
                .thenReturn("Explication automatique indisponible pour le moment (IA hors-ligne).");

        CopilotResponseDto reponse = copilotService.repondre(entrepriseId, "Pourquoi ce dossier n'est-il pas pret ?", principal);

        assertThat(reponse.answer()).containsIgnoringCase("indisponible");
    }

    @Test
    void verifie_lacces_avant_toute_classification_ou_collecte() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND));

        try {
            copilotService.repondre(entrepriseId, "Pourquoi ce dossier n'est-il pas pret ?", principal);
        } catch (org.springframework.web.server.ResponseStatusException ignored) {
            // attendu
        }

        verify(dossierComptableService, never()).generer(any(), any());
    }

    @Test
    void dirigeant_obtient_une_reponse_pour_une_intention_du_sous_ensemble_autorise() {
        DossierComptableDto dossier = new DossierComptableDto("SARL Test", 82, "A_SURVEILLER",
                List.of(new ScoreDomaineDto("Banque", 75)), List.of(), List.of());
        when(dossierComptableService.generer(entrepriseId, principal)).thenReturn(dossier);
        when(copilotExplainer.expliquer(any(), any(), any())).thenReturn("Votre dossier est a 82%.");

        CopilotResponseDto reponse = copilotService.repondrePourDirigeant(entrepriseId,
                "Pourquoi ce dossier n'est-il pas pret ?", principal);

        assertThat(reponse.answer()).isEqualTo("Votre dossier est a 82%.");
        assertThat(reponse.confidence()).isGreaterThan(0);
    }

    @Test
    void dirigeant_ne_peut_pas_declencher_une_intention_hors_sous_ensemble_meme_par_mots_cles() {
        CopilotResponseDto reponse = copilotService.repondrePourDirigeant(entrepriseId,
                "Quelle est l'evolution de la TVA ce mois-ci ?", principal);

        assertThat(reponse.confidence()).isZero();
        assertThat(reponse.evidence()).isEmpty();
        verifyNoInteractions(declarationFiscaleRepository, copilotExplainer);
    }

    @Test
    void dirigeant_ne_peut_pas_declencher_lintention_anomalies_fournisseurs() {
        CopilotResponseDto reponse = copilotService.repondrePourDirigeant(entrepriseId,
                "Y a-t-il une anomalie chez un fournisseur ?", principal);

        assertThat(reponse.confidence()).isZero();
        verifyNoInteractions(findingRepository, copilotExplainer);
    }

    @Test
    void dirigeant_verifie_lacces_avant_toute_classification() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN));

        try {
            copilotService.repondrePourDirigeant(entrepriseId, "Pourquoi ce dossier n'est-il pas pret ?", principal);
        } catch (org.springframework.web.server.ResponseStatusException ignored) {
            // attendu
        }

        verify(dossierComptableService, never()).generer(any(), any());
    }

    private FindingDto finding(String type, String sourceType, String sourceId) {
        return new FindingDto(UUID.randomUUID(), type, SeveriteFinding.IMPORTANT.name(), sourceType, sourceId,
                "Justificatif manquant", "description", null, null, "Contacter le client",
                StatutFinding.OPEN.name(), OffsetDateTime.now(), null, null, null, null,
                type, "DOCUMENT", "WARNING", null, null, OffsetDateTime.now());
    }
}
