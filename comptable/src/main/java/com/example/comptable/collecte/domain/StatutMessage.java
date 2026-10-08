package com.example.comptable.collecte.domain;

/**
 * Etat d'un message sortant. {@code SENT} = le fournisseur a ACCEPTE le message ; ce n'est PAS « delivre » ni « lu » :
 * ces etats n'existent pas ici tant que le fournisseur utilise ne les remonte pas reellement.
 */
public enum StatutMessage {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    /** Plus aucune demande ouverte a servir (annulee ou deja recue) : jamais envoye. */
    CANCELLED
}
