package com.example.comptable.agent.imputation.domain;

public enum StatutImputationSuggestion {
    /** Proposee, en attente de validation humaine (ou d'auto-application niveau 3). */
    PROPOSED,
    /** Validee par un comptable, pas encore appliquee (ex: en attente d'une action groupee). */
    ACCEPTED,
    REJECTED,
    /** Ecriture reellement creee (via ComptabilisationService.comptabiliser), manuellement ou en
     * niveau 3 automatique - etat terminal, jamais reversible via cette table. */
    APPLIED
}
