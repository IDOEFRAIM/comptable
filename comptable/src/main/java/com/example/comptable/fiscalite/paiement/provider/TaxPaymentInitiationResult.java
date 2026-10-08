package com.example.comptable.fiscalite.paiement.provider;

import com.example.comptable.fiscalite.paiement.domain.StatutPaiement;

public record TaxPaymentInitiationResult(
        StatutPaiement statut,
        String referenceExterne,
        String message
) {
    public static TaxPaymentInitiationResult enAttente(String referenceExterne, String message) {
        return new TaxPaymentInitiationResult(StatutPaiement.EN_ATTENTE_CONFIRMATION, referenceExterne, message);
    }

    public static TaxPaymentInitiationResult echec(String message) {
        return new TaxPaymentInitiationResult(StatutPaiement.ECHEC, null, message);
    }
}
