package com.example.comptable.copilot;

import com.example.comptable.cabinet.CabinetCockpitService;
import com.example.comptable.cabinet.InboxService;
import com.example.comptable.cabinet.web.dto.CabinetCockpitDto;
import com.example.comptable.cabinet.web.dto.ClientHealthDto;
import com.example.comptable.cabinet.web.dto.RisqueClientDto;
import com.example.comptable.cabinet.web.dto.WorkloadDto;
import com.example.comptable.copilot.web.dto.CopilotResponseDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CabinetCopilotServiceTest {

    @Mock
    private CabinetCockpitService cabinetCockpitService;
    @Mock
    private InboxService inboxService;
    @Mock
    private CopilotExplainer copilotExplainer;
    @Mock
    private CopilotTelemetryRecorder telemetryRecorder;

    @InjectMocks
    private CabinetCopilotService cabinetCopilotService;

    private UtilisateurPrincipal principal;

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("expert@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Traore");
        utilisateur.setPrenom("Karim");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        lenient().when(copilotExplainer.expliquer(any(), any(), any())).thenReturn("Explication.");
    }

    @Test
    void clients_bloques_reutilise_le_cockpit_sans_recalcul() {
        ClientHealthDto bloque = new ClientHealthDto(UUID.randomUUID(), "SARL En Difficulte", 40, "BLOQUE");
        CabinetCockpitDto cockpit = new CabinetCockpitDto(5, 3, 1, 1, 1, 0, 0, 0, 0, List.of(bloque), List.of());
        when(cabinetCockpitService.generer(principal)).thenReturn(cockpit);

        CopilotResponseDto reponse = cabinetCopilotService.repondre("Quels clients sont bloqués ?", principal);

        assertThat(reponse.evidence()).hasSize(1);
        assertThat(reponse.evidence().get(0).sourceId()).isEqualTo(bloque.entrepriseId().toString());
    }

    @Test
    void anomalies_importantes_reutilise_le_top_risques_du_cockpit() {
        RisqueClientDto risque = new RisqueClientDto(UUID.randomUUID(), "SARL Risque", "CRITICAL",
                "Declaration TVA en retard", "2026-08-01", "Deposer la declaration");
        CabinetCockpitDto cockpit = new CabinetCockpitDto(5, 4, 0, 1, 1, 0, 0, 1, 0, List.of(), List.of(risque));
        when(cabinetCockpitService.generer(principal)).thenReturn(cockpit);

        CopilotResponseDto reponse = cabinetCopilotService.repondre("Quels clients ont les anomalies les plus importantes ?", principal);

        assertThat(reponse.evidence()).hasSize(1);
    }

    @Test
    void backlog_collaborateur_reutilise_linbox_service() {
        WorkloadDto workload = new WorkloadDto(UUID.randomUUID(), "Sophie Sawadogo", 12, 3);
        when(inboxService.chargeParCollaborateur(principal)).thenReturn(List.of(workload));

        CopilotResponseDto reponse = cabinetCopilotService.repondre("Quel collaborateur a le plus gros backlog ?", principal);

        assertThat(reponse.answer()).isEqualTo("Explication.");
    }

    @Test
    void question_hors_perimetre_ne_declenche_aucun_appel() {
        CopilotResponseDto reponse = cabinetCopilotService.repondre("Quel temps fait-il ?", principal);

        assertThat(reponse.confidence()).isZero();
        verifyNoInteractions(cabinetCockpitService, inboxService, copilotExplainer);
    }
}
