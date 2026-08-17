package com.example.comptable.document.domain;

public enum StatutDocument {
    /** Depose, pas encore traite par l'OCR. */
    EN_ATTENTE,
    /** Extraction OCR en cours. */
    EN_COURS_TRAITEMENT,
    /** Extraction reussie, en attente de validation par un collaborateur. */
    TRAITE,
    /** Extraction impossible ou en echec : saisie manuelle requise. */
    ERREUR,
    /** Pipeline agentique (agent.pipeline) demarre et actuellement en pause au noeud
     * attente_validation : un checkpoint existe dans agent_checkpoint, la reprise
     * (PieceJustificativeAgentService.reprendreApresValidation) est possible. */
    EN_ATTENTE_VALIDATION_HUMAINE
}
