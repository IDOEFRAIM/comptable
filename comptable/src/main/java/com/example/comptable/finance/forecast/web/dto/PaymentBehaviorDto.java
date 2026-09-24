package com.example.comptable.finance.forecast.web.dto;

import java.util.UUID;

/**
 * Comportement de paiement historique d'un client (mission P2, section 11 du plan) - calcule a
 * partir des lignes DEJA lettrees (paiements reellement recus, correles a l'echeance
 * contractuelle de la facture d'origine), jamais une supposition. {@code sufficientHistory=false}
 * (moins de {@link com.example.comptable.finance.forecast.PaymentBehaviorService#ECHANTILLON_MINIMUM}
 * paiements reconcilies) => {@code medianDelayDays}/{@code averageDelayDays} restent {@code null},
 * jamais une estimation batie sur un echantillon trop faible pour etre fiable.
 */
public record PaymentBehaviorDto(
        UUID tiersId,
        String tiersNom,
        int sampleSize,
        Long medianDelayDays,
        Double averageDelayDays,
        boolean sufficientHistory
) {
}
