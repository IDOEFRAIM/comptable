package com.example.comptable.telemetry;

import com.example.comptable.agent.imputation.domain.ImputationSuggestion;
import com.example.comptable.agent.imputation.domain.StatutImputationSuggestion;
import com.example.comptable.agent.imputation.repository.ImputationSuggestionRepository;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.matching.domain.AiMatchProposal;
import com.example.comptable.agent.matching.domain.StatutPropositionIA;
import com.example.comptable.agent.matching.repository.AiMatchProposalRepository;
import com.example.comptable.copilot.domain.CopilotQuery;
import com.example.comptable.copilot.repository.CopilotQueryRepository;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.telemetry.web.dto.ProductKpiDto;
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
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductKpiServiceTest {

    @Mock
    private EntrepriseRepository entrepriseRepository;
    @Mock
    private FindingRepository findingRepository;
    @Mock
    private DemandePieceRepository demandePieceRepository;
    @Mock
    private AiMatchProposalRepository aiMatchProposalRepository;
    @Mock
    private ImputationSuggestionRepository imputationSuggestionRepository;
    @Mock
    private CopilotQueryRepository copilotQueryRepository;

    @InjectMocks
    private ProductKpiService productKpiService;

    private UtilisateurPrincipal principal;
    private UUID tenantId;
    private UUID entrepriseId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        entrepriseId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("expert@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Kabore");
        utilisateur.setPrenom("Rasmane");
        utilisateur.setTenant(tenant);
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        Entreprise entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        lenient().when(entrepriseRepository.findByTenantId(tenantId)).thenReturn(List.of(entreprise));
    }

    @Test
    void portefeuille_vide_ne_produit_aucun_pourcentage_invente() {
        when(entrepriseRepository.findByTenantId(tenantId)).thenReturn(List.of());

        ProductKpiDto kpi = productKpiService.generer(principal);

        assertThat(kpi.humanTouchRate().humanTouchRatePourcent()).isNull();
        assertThat(kpi.matchingBancaire().tauxAcceptationPourcent()).isNull();
        assertThat(kpi.resolutionPreuves().tauxResolutionPourcent()).isNull();
        assertThat(kpi.tempsResolutionFindings().moyenneHeures()).isNull();
        assertThat(kpi.usageCopilot().tauxFallbackPourcent()).isNull();
        assertThat(kpi.workQueue().findingsOuverts()).isZero();
    }

    @Test
    void aucune_proposition_ne_produit_un_taux_null_pas_zero() {
        when(aiMatchProposalRepository.findByEntrepriseIdIn(any())).thenReturn(List.of());
        when(imputationSuggestionRepository.findByEntrepriseIdIn(any())).thenReturn(List.of());
        stubsVidesRestants();

        ProductKpiDto kpi = productKpiService.generer(principal);

        assertThat(kpi.matchingBancaire().propositions()).isZero();
        assertThat(kpi.matchingBancaire().tauxAcceptationPourcent()).isNull();
        assertThat(kpi.matchingBancaire().tauxRejetPourcent()).isNull();
    }

    @Test
    void classe_correctement_automatique_assiste_et_humain_seul() {
        AiMatchProposal autoApplique = proposition(StatutPropositionIA.ACCEPTED, null);
        AiMatchProposal accepteParHumain = proposition(StatutPropositionIA.ACCEPTED, principal);
        AiMatchProposal rejeteParHumain = proposition(StatutPropositionIA.REJECTED, principal);
        AiMatchProposal expiree = proposition(StatutPropositionIA.EXPIRED, null);
        AiMatchProposal enAttente = proposition(StatutPropositionIA.PROPOSED, null);

        when(aiMatchProposalRepository.findByEntrepriseIdIn(any()))
                .thenReturn(List.of(autoApplique, accepteParHumain, rejeteParHumain, expiree, enAttente));
        when(imputationSuggestionRepository.findByEntrepriseIdIn(any())).thenReturn(List.of());
        stubsVidesRestants();

        ProductKpiDto kpi = productKpiService.generer(principal);

        assertThat(kpi.humanTouchRate().automatique()).isEqualTo(1);
        assertThat(kpi.humanTouchRate().assisteParIa()).isEqualTo(2);
        assertThat(kpi.humanTouchRate().humainSeul()).isEqualTo(1);
        assertThat(kpi.humanTouchRate().enAttente()).isEqualTo(1);
        assertThat(kpi.humanTouchRate().totalResolu()).isEqualTo(4);
        // 1 automatique / 4 resolus = 25% automation, 75% human touch
        assertThat(kpi.humanTouchRate().automationRatePourcent()).isEqualTo(25.0, offset(0.01));
        assertThat(kpi.humanTouchRate().humanTouchRatePourcent()).isEqualTo(75.0, offset(0.01));
    }

    @Test
    void matching_bancaire_calcule_acceptation_et_rejet_sur_les_seules_propositions_tranchees() {
        AiMatchProposal acceptee1 = proposition(StatutPropositionIA.ACCEPTED, principal);
        AiMatchProposal acceptee2 = proposition(StatutPropositionIA.ACCEPTED, principal);
        AiMatchProposal rejetee = proposition(StatutPropositionIA.REJECTED, principal);
        AiMatchProposal enAttente = proposition(StatutPropositionIA.PROPOSED, null);

        when(aiMatchProposalRepository.findByEntrepriseIdIn(any()))
                .thenReturn(List.of(acceptee1, acceptee2, rejetee, enAttente));
        when(imputationSuggestionRepository.findByEntrepriseIdIn(any())).thenReturn(List.of());
        stubsVidesRestants();

        ProductKpiDto kpi = productKpiService.generer(principal);

        assertThat(kpi.matchingBancaire().propositions()).isEqualTo(4);
        assertThat(kpi.matchingBancaire().acceptees()).isEqualTo(2);
        assertThat(kpi.matchingBancaire().rejetees()).isEqualTo(1);
        // 2 acceptees / 3 tranchees (enAttente exclue) = 66.67%
        assertThat(kpi.matchingBancaire().tauxAcceptationPourcent()).isEqualTo(200.0 / 3, offset(0.01));
    }

    @Test
    void temps_resolution_findings_calcule_moyenne_mediane_p90() {
        OffsetDateTime base = OffsetDateTime.now();
        Finding f1 = findingResolu(base, base.plusHours(1));
        Finding f2 = findingResolu(base, base.plusHours(2));
        Finding f3 = findingResolu(base, base.plusHours(3));

        when(findingRepository.findByEntrepriseIdInAndStatus(any(), org.mockito.ArgumentMatchers.eq(StatutFinding.RESOLVED)))
                .thenReturn(List.of(f1, f2, f3));
        stubsVidesSaufFindings();

        ProductKpiDto kpi = productKpiService.generer(principal);

        assertThat(kpi.tempsResolutionFindings().nombreResolu()).isEqualTo(3);
        assertThat(kpi.tempsResolutionFindings().moyenneHeures()).isEqualTo(2.0, offset(0.01));
        assertThat(kpi.tempsResolutionFindings().medianeHeures()).isEqualTo(2.0, offset(0.01));
    }

    @Test
    void usage_copilot_calcule_les_taux_de_feedback_sur_les_seules_reponses_notees() {
        CopilotQuery avecPreuvePositif = copilotQuery(2, false, "POSITIVE");
        CopilotQuery sansPreuveFallback = copilotQuery(0, true, null);
        CopilotQuery negatif = copilotQuery(1, false, "NEGATIVE");

        when(copilotQueryRepository.findByTenantIdAndCreatedAtAfter(any(), any()))
                .thenReturn(List.of(avecPreuvePositif, sansPreuveFallback, negatif));
        stubsVidesSaufCopilot();

        ProductKpiDto kpi = productKpiService.generer(principal);

        assertThat(kpi.usageCopilot().questions()).isEqualTo(3);
        assertThat(kpi.usageCopilot().feedbackPositif()).isEqualTo(1);
        assertThat(kpi.usageCopilot().feedbackNegatif()).isEqualTo(1);
        assertThat(kpi.usageCopilot().sansFeedback()).isEqualTo(1);
        // 1 fallback / 3 questions = 33.33%
        assertThat(kpi.usageCopilot().tauxFallbackPourcent()).isEqualTo(100.0 / 3, offset(0.01));
        // 1 positif / 2 notees (positif+negatif) = 50%
        assertThat(kpi.usageCopilot().tauxFeedbackPositifPourcent()).isEqualTo(50.0, offset(0.01));
    }

    // --- Helpers ---------------------------------------------------------------------------

    private AiMatchProposal proposition(StatutPropositionIA statut, UtilisateurPrincipal validateur) {
        AiMatchProposal p = new AiMatchProposal();
        p.setId(UUID.randomUUID());
        p.setStatus(statut);
        if (validateur != null) {
            Utilisateur u = new Utilisateur();
            u.setId(validateur.getId());
            p.setValidatedBy(u);
        }
        return p;
    }

    private Finding findingResolu(OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
        Finding f = new Finding();
        f.setId(UUID.randomUUID());
        f.setType(TypeFinding.MISSING_EVIDENCE);
        f.setSeverity(SeveriteFinding.IMPORTANT);
        f.setSourceType("LIGNE_RELEVE");
        f.setSourceId("ligne-1");
        f.setTitle("Justificatif manquant");
        f.setStatus(StatutFinding.RESOLVED);
        f.setCreatedAt(createdAt);
        f.setResolvedAt(resolvedAt);
        return f;
    }

    private CopilotQuery copilotQuery(int evidenceCount, boolean fallback, String feedback) {
        CopilotQuery q = new CopilotQuery();
        q.setId(UUID.randomUUID());
        q.setTenantId(tenantId);
        q.setPortee("COMPTABLE");
        q.setQuestion("Question de test");
        q.setIntent("A_TRAITER_MAINTENANT");
        q.setConfidence(fallback ? 0.0 : 0.9);
        q.setEvidenceCount(evidenceCount);
        q.setFallback(fallback);
        q.setFeedback(feedback);
        return q;
    }

    private void stubsVidesRestants() {
        lenient().when(findingRepository.findByEntrepriseIdInAndStatus(any(), any())).thenReturn(List.of());
        lenient().when(findingRepository.findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(any(), any())).thenReturn(List.of());
        lenient().when(demandePieceRepository.countByEntrepriseIdInAndStatut(any(), any())).thenReturn(0L);
        lenient().when(demandePieceRepository.findByEntrepriseIdInAndStatutOrderByDateLimiteAsc(any(), any())).thenReturn(List.of());
        lenient().when(demandePieceRepository.countByEntrepriseIdInAndStatutAndDateLimiteBefore(any(), any(), any())).thenReturn(0L);
        lenient().when(copilotQueryRepository.findByTenantIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());
    }

    private void stubsVidesSaufFindings() {
        lenient().when(aiMatchProposalRepository.findByEntrepriseIdIn(any())).thenReturn(List.of());
        lenient().when(imputationSuggestionRepository.findByEntrepriseIdIn(any())).thenReturn(List.of());
        lenient().when(findingRepository.findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(any(), any())).thenReturn(List.of());
        lenient().when(demandePieceRepository.countByEntrepriseIdInAndStatut(any(), any())).thenReturn(0L);
        lenient().when(demandePieceRepository.findByEntrepriseIdInAndStatutOrderByDateLimiteAsc(any(), any())).thenReturn(List.of());
        lenient().when(demandePieceRepository.countByEntrepriseIdInAndStatutAndDateLimiteBefore(any(), any(), any())).thenReturn(0L);
        lenient().when(copilotQueryRepository.findByTenantIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());
    }

    private void stubsVidesSaufCopilot() {
        lenient().when(aiMatchProposalRepository.findByEntrepriseIdIn(any())).thenReturn(List.of());
        lenient().when(imputationSuggestionRepository.findByEntrepriseIdIn(any())).thenReturn(List.of());
        lenient().when(findingRepository.findByEntrepriseIdInAndStatus(any(), any())).thenReturn(List.of());
        lenient().when(findingRepository.findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(any(), any())).thenReturn(List.of());
        lenient().when(demandePieceRepository.countByEntrepriseIdInAndStatut(any(), any())).thenReturn(0L);
        lenient().when(demandePieceRepository.findByEntrepriseIdInAndStatutOrderByDateLimiteAsc(any(), any())).thenReturn(List.of());
        lenient().when(demandePieceRepository.countByEntrepriseIdInAndStatutAndDateLimiteBefore(any(), any(), any())).thenReturn(0L);
    }
}
