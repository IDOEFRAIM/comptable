package com.example.comptable.agent.matching.domain;

/** Decision du Java Control (MatchDecisionEngine), distincte du {@code decision} brut renvoye
 * par ai-service (MatchDecision Python : MATCH_PROPOSED/PARTIAL_MATCH/AMBIGUOUS_MATCH/NO_MATCH) -
 * celle-ci integre en plus les garde-fous metier Java (section 8/9 de la mission) avant de
 * statuer sur la suite reellement donnee au rapprochement. */
public enum DecisionRapprochementIA {
    /** Confiance suffisante ET tous les garde-fous metier passes : pointage applique
     * automatiquement (uniquement pour un candidat ACCOUNTING_ENTRY, cf. MatchDecisionEngine). */
    MATCH_AUTO,
    /** Proposition plausible mais necessitant une validation humaine avant tout effet. */
    MATCH_REVIEW,
    /** Aucune correspondance plausible retenue - resultat valide, pas un echec. */
    NO_MATCH,
    /** Le microservice IA etait indisponible (timeout, erreur reseau, panne) : aucune proposition
     * IA, le rapprochement manuel/existant reste la seule voie pour cette ligne. */
    AI_UNAVAILABLE
}
