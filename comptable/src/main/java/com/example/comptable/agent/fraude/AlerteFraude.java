package com.example.comptable.agent.fraude;

/**
 * Un drapeau rouge individuel remonte par {@link AnalyseFraudeService}. {@code code} est une
 * cle courte, stable et machine-lisible (utile pour un futur filtrage/statistiques dans
 * l'inbox), {@code motif} explique CE qui a ete detecte (le diagnostic), {@code actionRecommandee}
 * dit AU COMPTABLE quoi faire concretement (le geste suivant) - Sprint 8 (explicabilite
 * pedagogique) : un comptable de terrain doit pouvoir agir sans avoir a interpreter le motif.
 */
public record AlerteFraude(String code, String motif, NiveauRisqueFraude gravite, String actionRecommandee) {
}
