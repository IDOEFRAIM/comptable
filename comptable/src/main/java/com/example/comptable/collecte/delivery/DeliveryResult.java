package com.example.comptable.collecte.delivery;

/**
 * Resultat d'un envoi. {@code SENT} : le fournisseur a accepte le message (jamais « delivre » ni « lu »).
 * {@code RETRYABLE} : panne technique (timeout, 5xx, limite de debit) - on retente plus tard.
 * {@code PERMANENT} : refus definitif (destinataire invalide, template refuse) - inutile de retenter.
 */
public record DeliveryResult(Issue issue, String providerMessageId, String erreur) {

    public enum Issue { SENT, RETRYABLE, PERMANENT }

    public static DeliveryResult envoye(String providerMessageId) {
        return new DeliveryResult(Issue.SENT, providerMessageId, null);
    }

    public static DeliveryResult reessayable(String erreur) {
        return new DeliveryResult(Issue.RETRYABLE, null, erreur);
    }

    public static DeliveryResult definitif(String erreur) {
        return new DeliveryResult(Issue.PERMANENT, null, erreur);
    }
}
