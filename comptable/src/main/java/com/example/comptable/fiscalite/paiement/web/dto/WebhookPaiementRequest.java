package com.example.comptable.fiscalite.paiement.web.dto;

/**
 * Format generique provisoire pour la confirmation asynchrone d'un paiement - voir la javadoc de
 * {@code PaiementWebhookController}. A remplacer par le format reel documente par chaque
 * fournisseur (champs, encodage, verification de signature) une fois l'integration branchee.
 */
public record WebhookPaiementRequest(String referenceExterne, boolean reussite, String message) {
}
