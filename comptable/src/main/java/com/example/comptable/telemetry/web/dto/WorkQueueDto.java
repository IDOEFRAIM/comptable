package com.example.comptable.telemetry.web.dto;

/** Mission P1.9 (section 17) : etat courant de la file de travail unifiee (Finding + DemandePiece
 * ouverts), tout le portefeuille du tenant - pas de metrique RH, uniquement du pilotage de
 * production (mission section 18). */
public record WorkQueueDto(
        int findingsOuverts,
        int findingsEnRetard,
        int demandesEnAttente,
        int demandesEnRetard
) {
}
