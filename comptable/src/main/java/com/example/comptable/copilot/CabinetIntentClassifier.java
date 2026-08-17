package com.example.comptable.copilot;

import com.example.comptable.copilot.domain.IntentCopilotCabinet;

/** Routage intelligent niveau cabinet (mission P1, section 40) - meme discipline que
 * {@link IntentClassifier} (deterministe, pas de LLM), jeu de questions distinct. */
public final class CabinetIntentClassifier {

    private CabinetIntentClassifier() {
    }

    public static IntentCopilotCabinet classifier(String question) {
        String q = TexteNormalizer.normaliser(question);

        if (q.contains("client") && q.contains("bloque")) {
            return IntentCopilotCabinet.CLIENTS_BLOQUES;
        }
        if (q.contains("demande") && q.contains("retard")) {
            return IntentCopilotCabinet.DEMANDES_EN_RETARD;
        }
        if (q.contains("collaborateur") && (q.contains("backlog") || q.contains("charge"))) {
            return IntentCopilotCabinet.BACKLOG_COLLABORATEUR;
        }
        if (q.contains("cloture") && q.contains("risque")) {
            return IntentCopilotCabinet.CLOTURES_A_RISQUE;
        }
        if (q.contains("anomal")) {
            return IntentCopilotCabinet.ANOMALIES_IMPORTANTES;
        }
        return IntentCopilotCabinet.INCONNU;
    }
}
