package com.example.comptable.copilot;

import com.example.comptable.copilot.domain.CopilotQuery;
import com.example.comptable.copilot.repository.CopilotQueryRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotFeedbackServiceTest {

    @Mock
    private CopilotQueryRepository copilotQueryRepository;

    @InjectMocks
    private CopilotFeedbackService copilotFeedbackService;

    private UtilisateurPrincipal principal;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
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
    }

    private CopilotQuery query(UUID tenantIdDeLaQuery) {
        CopilotQuery q = new CopilotQuery();
        q.setId(UUID.randomUUID());
        q.setTenantId(tenantIdDeLaQuery);
        q.setPortee("COMPTABLE");
        q.setQuestion("Pourquoi ce dossier n'est-il pas pret ?");
        q.setIntent("POURQUOI_DOSSIER_PAS_PRET");
        q.setConfidence(0.9);
        q.setEvidenceCount(2);
        q.setFallback(false);
        return q;
    }

    @Test
    void enregistre_un_feedback_positif() {
        CopilotQuery q = query(tenantId);
        when(copilotQueryRepository.findById(q.getId())).thenReturn(Optional.of(q));
        when(copilotQueryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        copilotFeedbackService.enregistrerFeedback(q.getId(), "POSITIVE", null, principal);

        ArgumentCaptor<CopilotQuery> captor = ArgumentCaptor.forClass(CopilotQuery.class);
        verify(copilotQueryRepository).save(captor.capture());
        assertThat(captor.getValue().getFeedback()).isEqualTo("POSITIVE");
        assertThat(captor.getValue().getFeedbackAt()).isNotNull();
    }

    @Test
    void enregistre_un_feedback_negatif_avec_motif() {
        CopilotQuery q = query(tenantId);
        when(copilotQueryRepository.findById(q.getId())).thenReturn(Optional.of(q));
        when(copilotQueryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        copilotFeedbackService.enregistrerFeedback(q.getId(), "NEGATIVE", "incomplet", principal);

        ArgumentCaptor<CopilotQuery> captor = ArgumentCaptor.forClass(CopilotQuery.class);
        verify(copilotQueryRepository).save(captor.capture());
        assertThat(captor.getValue().getFeedback()).isEqualTo("NEGATIVE");
        assertThat(captor.getValue().getFeedbackReason()).isEqualTo("incomplet");
    }

    @Test
    void refuse_un_feedback_dune_autre_valeur() {
        assertThatThrownBy(() -> copilotFeedbackService.enregistrerFeedback(UUID.randomUUID(), "MAYBE", null, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("POSITIVE ou NEGATIVE");
        verify(copilotQueryRepository, never()).save(any());
    }

    @Test
    void refuse_un_feedback_sur_une_question_dun_autre_tenant() {
        CopilotQuery q = query(UUID.randomUUID());
        when(copilotQueryRepository.findById(q.getId())).thenReturn(Optional.of(q));

        assertThatThrownBy(() -> copilotFeedbackService.enregistrerFeedback(q.getId(), "POSITIVE", null, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
        verify(copilotQueryRepository, never()).save(any());
    }

    @Test
    void refuse_un_feedback_sur_une_question_inexistante() {
        UUID id = UUID.randomUUID();
        when(copilotQueryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> copilotFeedbackService.enregistrerFeedback(id, "POSITIVE", null, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
    }
}
