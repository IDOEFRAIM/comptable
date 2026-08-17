package com.example.comptable.telemetry.web.dto;

/** Mission P1.9 (section 12) : moyenne/mediane/P90 en heures, sur les Finding RESOLVED du
 * portefeuille. Null si aucun Finding resolu (jamais 0, qui laisserait croire a une resolution
 * instantanee). */
public record FindingResolutionTimeDto(
        int nombreResolu,
        Double moyenneHeures,
        Double medianeHeures,
        Double p90Heures
) {
}
