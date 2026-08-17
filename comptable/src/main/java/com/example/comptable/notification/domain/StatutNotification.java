package com.example.comptable.notification.domain;

/**
 * Reutilise la colonne "statut" de la table notification (initialement pensee pour
 * un pipeline d'envoi A_ENVOYER/ENVOYEE) avec une semantique de lecture in-app :
 * NON_LUE tant que l'utilisateur n'a pas ouvert la notification, LUE ensuite.
 */
public enum StatutNotification {
    NON_LUE,
    LUE
}
