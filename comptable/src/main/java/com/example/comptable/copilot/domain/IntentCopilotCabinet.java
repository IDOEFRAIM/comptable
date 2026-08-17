package com.example.comptable.copilot.domain;

/** Mission P1 (section 40) : Copilot niveau cabinet - jeu ferme distinct du Copilot comptable
 * (perimetre cross-entreprises, jamais un entrepriseId en parametre). */
public enum IntentCopilotCabinet {
    CLIENTS_BLOQUES,
    DEMANDES_EN_RETARD,
    BACKLOG_COLLABORATEUR,
    CLOTURES_A_RISQUE,
    ANOMALIES_IMPORTANTES,
    INCONNU
}
