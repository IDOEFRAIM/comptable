package com.example.comptable.copilot;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.copilot.dto.CopilotExplainResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotExplainerTest {

    @Mock
    private AiServiceClient aiServiceClient;

    @InjectMocks
    private CopilotExplainer copilotExplainer;

    @BeforeEach
    void setUp() {
        // rien a preparer par defaut
    }

    @Test
    void retourne_lexplication_du_microservice_ia() {
        when(aiServiceClient.copilotExplain(any()))
                .thenReturn(Optional.of(new CopilotExplainResponseDTO("Explication generee.")));

        String reponse = copilotExplainer.expliquer("Question", "INTENT", Map.of());

        assertThat(reponse).isEqualTo("Explication generee.");
    }

    @Test
    void degrade_proprement_quand_le_microservice_ia_est_indisponible() {
        when(aiServiceClient.copilotExplain(any())).thenReturn(Optional.empty());

        String reponse = copilotExplainer.expliquer("Question", "INTENT", Map.of());

        assertThat(reponse).containsIgnoringCase("indisponible");
    }
}
