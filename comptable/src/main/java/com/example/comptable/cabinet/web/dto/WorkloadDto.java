package com.example.comptable.cabinet.web.dto;

import java.util.UUID;

/** Mission P1 (section 13) : mesure simple de charge, jamais utilisee pour de l'allocation RH
 * automatique (mission section 13/38 : "l'affectation reste humaine en P1"). */
public record WorkloadDto(
        UUID collaborateurId,
        String collaborateurNom,
        int totalTaches,
        int tachesCritiques
) {
}
