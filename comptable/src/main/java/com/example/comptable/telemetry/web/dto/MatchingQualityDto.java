package com.example.comptable.telemetry.web.dto;

/** Mission P1.9 (section 8) : {@code domaine} = "BANCAIRE" (AiMatchProposal) ou
 * "COMPTABILISATION" (AccountingProposal) - jamais fusionnes en un seul total (deux moteurs
 * distincts, deux taux distincts, voir docs/P1_9_AUDIT.md section H). */
public record MatchingQualityDto(
        String domaine,
        int propositions,
        int acceptees,
        int rejetees,
        int enAttente,
        int expirees,
        Double tauxAcceptationPourcent,
        Double tauxRejetPourcent
) {
}
