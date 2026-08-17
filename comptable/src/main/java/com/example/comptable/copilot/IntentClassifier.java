package com.example.comptable.copilot;

import com.example.comptable.copilot.domain.IntentCopilot;

/**
 * Routage intelligent (mission P1, section 17) : classification 100% deterministe (mots-cles, pas
 * de LLM) de la question vers une des capacites structurees supportees - "Ne pas envoyer toutes
 * les questions directement au LLM." Volontairement simple (correspondance de mots-cles sur le
 * texte normalise) plutot qu'un modele de classification : le jeu de questions est ferme et petit
 * (section 14), un classifieur statistique serait une complexite non justifiee ici.
 */
public final class IntentClassifier {

    private IntentClassifier() {
    }

    public static IntentCopilot classifier(String question) {
        String q = TexteNormalizer.normaliser(question);

        if (q.contains("pret") && q.contains("dossier")) {
            return IntentCopilot.POURQUOI_DOSSIER_PAS_PRET;
        }
        if (q.contains("bloque") && (q.contains("cloture") || q.contains("clore"))) {
            return IntentCopilot.BLOQUANTS_CLOTURE;
        }
        if (q.contains("solde") && (q.contains("banque") || q.contains("bancaire"))) {
            return IntentCopilot.ECART_BANQUE;
        }
        if (q.contains("piece") && (q.contains("manqu") || q.contains("absent"))) {
            return IntentCopilot.PIECES_MANQUANTES;
        }
        if (q.contains("fournisseur") && (q.contains("anomal") || q.contains("probleme"))) {
            return IntentCopilot.ANOMALIES_FOURNISSEURS;
        }
        if (q.contains("client") && q.contains("retard")) {
            return IntentCopilot.RETARDS_CLIENTS;
        }
        if (q.contains("tva")) {
            return IntentCopilot.EVOLUTION_TVA;
        }
        if (q.contains("traiter") || (q.contains("faire") && (q.contains("maintenant") || q.contains("aujourd")))) {
            return IntentCopilot.A_TRAITER_MAINTENANT;
        }
        return IntentCopilot.INCONNU;
    }
}
