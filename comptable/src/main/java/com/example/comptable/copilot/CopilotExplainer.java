package com.example.comptable.copilot;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.copilot.dto.CopilotExplainRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Appel LLM partage entre {@link CopilotService} (niveau comptable) et le Copilot niveau cabinet
 * (mission P1, section 40) - meme contrat, memes garde-fous, jamais duplique. */
@Component
@RequiredArgsConstructor
public class CopilotExplainer {

    private final AiServiceClient aiServiceClient;

    public String expliquer(String question, String intent, Map<String, Object> facts) {
        return aiServiceClient
                .copilotExplain(new CopilotExplainRequestDTO(question, intent, facts))
                .map(reponse -> reponse.explanation())
                .orElse("Explication automatique indisponible pour le moment (IA hors-ligne) - "
                        + "les donnees verifiees ci-dessus restent fiables et a jour.");
    }
}
