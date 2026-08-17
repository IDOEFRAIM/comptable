package com.example.comptable.copilot;

import com.example.comptable.copilot.domain.IntentCopilot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentClassifierTest {

    @Test
    void classifie_les_8_questions_obligatoires_de_la_mission() {
        assertThat(IntentClassifier.classifier("Pourquoi ce dossier n'est-il pas prêt ?"))
                .isEqualTo(IntentCopilot.POURQUOI_DOSSIER_PAS_PRET);
        assertThat(IntentClassifier.classifier("Qu'est-ce qui bloque la clôture ?"))
                .isEqualTo(IntentCopilot.BLOQUANTS_CLOTURE);
        assertThat(IntentClassifier.classifier("Pourquoi le solde bancaire ne correspond-il pas ?"))
                .isEqualTo(IntentCopilot.ECART_BANQUE);
        assertThat(IntentClassifier.classifier("Quelles sont les pièces manquantes ?"))
                .isEqualTo(IntentCopilot.PIECES_MANQUANTES);
        assertThat(IntentClassifier.classifier("Quels fournisseurs ont des anomalies ?"))
                .isEqualTo(IntentCopilot.ANOMALIES_FOURNISSEURS);
        assertThat(IntentClassifier.classifier("Quels clients ont des retards ?"))
                .isEqualTo(IntentCopilot.RETARDS_CLIENTS);
        assertThat(IntentClassifier.classifier("Pourquoi la TVA a augmenté ?"))
                .isEqualTo(IntentCopilot.EVOLUTION_TVA);
        assertThat(IntentClassifier.classifier("Que dois-je traiter maintenant ?"))
                .isEqualTo(IntentCopilot.A_TRAITER_MAINTENANT);
    }

    /** Mission P1.5 (section 46) : les 6 libelles de suggestion du Copilot Dirigeant
     * (frontend/src/app/features/copilot/copilot-dirigeant-widget.component.ts) doivent rester
     * reconnus par ce classifieur - un libelle "convivial" qui derive des mots-cles exacts tombe
     * silencieusement dans la reponse de repli. Bug reel trouve en testant l'endpoint en direct,
     * corrige cote Angular ; ce test verrouille la coherence pour l'avenir. */
    @Test
    void suggestions_du_copilot_dirigeant_restent_reconnues_par_le_classifieur() {
        assertThat(IntentClassifier.classifier("Mon dossier est-il prêt ?"))
                .isEqualTo(IntentCopilot.POURQUOI_DOSSIER_PAS_PRET);
        assertThat(IntentClassifier.classifier("Qu'est-ce qui bloque la clôture ?"))
                .isEqualTo(IntentCopilot.BLOQUANTS_CLOTURE);
        assertThat(IntentClassifier.classifier("Quel est mon solde bancaire ?"))
                .isEqualTo(IntentCopilot.ECART_BANQUE);
        assertThat(IntentClassifier.classifier("Quelles pièces me manquent ?"))
                .isEqualTo(IntentCopilot.PIECES_MANQUANTES);
        assertThat(IntentClassifier.classifier("Quels clients me doivent de l'argent en retard ?"))
                .isEqualTo(IntentCopilot.RETARDS_CLIENTS);
        assertThat(IntentClassifier.classifier("Que dois-je faire maintenant ?"))
                .isEqualTo(IntentCopilot.A_TRAITER_MAINTENANT);
    }

    @Test
    void question_hors_perimetre_est_inconnue() {
        assertThat(IntentClassifier.classifier("Quelle est la capitale du Burkina Faso ?"))
                .isEqualTo(IntentCopilot.INCONNU);
        assertThat(IntentClassifier.classifier("")).isEqualTo(IntentCopilot.INCONNU);
        assertThat(IntentClassifier.classifier(null)).isEqualTo(IntentCopilot.INCONNU);
    }
}
