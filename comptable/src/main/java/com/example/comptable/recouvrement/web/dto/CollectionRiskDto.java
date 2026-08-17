package com.example.comptable.recouvrement.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Mission P1 (section 26-28) : risque de recouvrement par client - toujours explicable (le
 * niveau derive directement de l'anciennete reelle du solde du, jamais d'un score opaque), jamais
 * envoye automatiquement au client (l'action recommandee reste un texte a valider par un humain). */
public record CollectionRiskDto(
        UUID tiersId,
        String tiersNom,
        BigDecimal soldeDu,
        String niveauRisque,
        String actionRecommandee
) {
}
