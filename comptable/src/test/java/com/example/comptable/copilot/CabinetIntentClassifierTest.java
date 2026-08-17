package com.example.comptable.copilot;

import com.example.comptable.copilot.domain.IntentCopilotCabinet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CabinetIntentClassifierTest {

    @Test
    void classifie_les_5_questions_cabinet_de_la_mission() {
        assertThat(CabinetIntentClassifier.classifier("Quels clients sont bloqués ?"))
                .isEqualTo(IntentCopilotCabinet.CLIENTS_BLOQUES);
        assertThat(CabinetIntentClassifier.classifier("Quels dossiers ont le plus de demandes en retard ?"))
                .isEqualTo(IntentCopilotCabinet.DEMANDES_EN_RETARD);
        assertThat(CabinetIntentClassifier.classifier("Quel collaborateur a le plus gros backlog ?"))
                .isEqualTo(IntentCopilotCabinet.BACKLOG_COLLABORATEUR);
        assertThat(CabinetIntentClassifier.classifier("Quelles clôtures sont à risque ?"))
                .isEqualTo(IntentCopilotCabinet.CLOTURES_A_RISQUE);
        assertThat(CabinetIntentClassifier.classifier("Quels clients ont les anomalies les plus importantes ?"))
                .isEqualTo(IntentCopilotCabinet.ANOMALIES_IMPORTANTES);
    }

    @Test
    void question_hors_perimetre_est_inconnue() {
        assertThat(CabinetIntentClassifier.classifier("Quelle heure est-il ?")).isEqualTo(IntentCopilotCabinet.INCONNU);
    }
}
