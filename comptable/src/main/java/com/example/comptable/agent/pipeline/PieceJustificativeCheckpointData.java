package com.example.comptable.agent.pipeline;

import com.example.comptable.agent.fraude.RapportFraude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Miroir typé de {@link PieceJustificativeState} utilisé uniquement pour la (de)serialisation
 * JSON des checkpoints par {@link JdbcCheckpointSaver}. Un round-trip Jackson "a l'aveugle" sur
 * {@code Map<String,Object>} reconstruirait {@link ExtractionPieceResult} en {@code LinkedHashMap}
 * generique ; passer par ce type explicite garantit que {@code objectMapper.readValue(...)}
 * reconstruit le bon type Java a chaque champ. Tous les champs sont nullables : un checkpoint
 * peut etre sauvegarde apres n'importe quel noeud, donc avec un etat encore partiel.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PieceJustificativeCheckpointData(
        UUID documentId,
        UUID entrepriseId,
        ExtractionPieceResult extraction,
        Boolean conformiteOk,
        String motifAlerteConformite,
        Boolean valideParHumain,
        RapportFraude rapportFraude
) {

    static PieceJustificativeCheckpointData depuisMap(Map<String, Object> state) {
        return new PieceJustificativeCheckpointData(
                (UUID) state.get(PieceJustificativeState.DOCUMENT_ID),
                (UUID) state.get(PieceJustificativeState.ENTREPRISE_ID),
                (ExtractionPieceResult) state.get(PieceJustificativeState.EXTRACTION),
                (Boolean) state.get(PieceJustificativeState.CONFORMITE_OK),
                (String) state.get(PieceJustificativeState.MOTIF_ALERTE_CONFORMITE),
                (Boolean) state.get(PieceJustificativeState.VALIDE_PAR_HUMAIN),
                (RapportFraude) state.get(PieceJustificativeState.RAPPORT_FRAUDE)
        );
    }

    Map<String, Object> versMap() {
        Map<String, Object> map = new HashMap<>();
        if (documentId != null) {
            map.put(PieceJustificativeState.DOCUMENT_ID, documentId);
        }
        if (entrepriseId != null) {
            map.put(PieceJustificativeState.ENTREPRISE_ID, entrepriseId);
        }
        if (extraction != null) {
            map.put(PieceJustificativeState.EXTRACTION, extraction);
        }
        if (conformiteOk != null) {
            map.put(PieceJustificativeState.CONFORMITE_OK, conformiteOk);
        }
        if (motifAlerteConformite != null) {
            map.put(PieceJustificativeState.MOTIF_ALERTE_CONFORMITE, motifAlerteConformite);
        }
        if (valideParHumain != null) {
            map.put(PieceJustificativeState.VALIDE_PAR_HUMAIN, valideParHumain);
        }
        if (rapportFraude != null) {
            map.put(PieceJustificativeState.RAPPORT_FRAUDE, rapportFraude);
        }
        return map;
    }
}
