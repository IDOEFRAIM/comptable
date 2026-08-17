package com.example.comptable.cabinet.web.dto;

import java.util.UUID;

/** Mission P1 (section 9) : pure projection du score deja calcule par le Dossier Comptable
 * Intelligent (mission P0) - aucun second algorithme de score n'est cree pour le cabinet. */
public record ClientHealthDto(
        UUID entrepriseId,
        String raisonSociale,
        int scoreGlobal,
        String statut
) {
}
