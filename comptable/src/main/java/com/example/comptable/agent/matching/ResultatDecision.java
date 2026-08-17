package com.example.comptable.agent.matching;

import com.example.comptable.agent.matching.domain.AiMatchProposal;
import com.example.comptable.agent.matching.domain.DecisionRapprochementIA;

import java.util.UUID;

/**
 * Issue de {@link MatchDecisionEngine#statuer} : la decision Java (qui integre les garde-fous
 * metier, distincte du {@code decision} brut d'ai-service), la proposition persistee (piste
 * d'audit), et l'identifiant de l'ecriture pointee si {@code decision == MATCH_AUTO} (null sinon
 * - jamais de pointage pour un candidat FACTURE/DOCUMENT, cf. MatchDecisionEngine).
 */
public record ResultatDecision(DecisionRapprochementIA decision, AiMatchProposal proposition, UUID ligneEcriturePointee) {
}
