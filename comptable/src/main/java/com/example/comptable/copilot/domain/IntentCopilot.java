package com.example.comptable.copilot.domain;

/** Mission P1 (section 14, 17) : jeu ferme de capacites structurees - pas de chat libre. Chaque
 * valeur correspond a une des questions obligatoires listees par la mission ; {@link #INCONNU}
 * ne declenche jamais d'appel LLM (mission section 48 : contexte minimal, jamais de question
 * hors-perimetre envoyee au provider). */
public enum IntentCopilot {
    POURQUOI_DOSSIER_PAS_PRET,
    BLOQUANTS_CLOTURE,
    ECART_BANQUE,
    PIECES_MANQUANTES,
    ANOMALIES_FOURNISSEURS,
    RETARDS_CLIENTS,
    EVOLUTION_TVA,
    A_TRAITER_MAINTENANT,
    INCONNU
}
