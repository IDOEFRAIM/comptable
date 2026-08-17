package com.example.comptable.cabinet.web.dto;

import java.util.List;

/** Mission P1 (section 8) : vue globale du cabinet - jamais persistee, recalculee a la lecture a
 * partir des memes {@code Finding}/{@code DossierComptableDto} que la vue par entreprise (P0). */
public record CabinetCockpitDto(
        int totalClients,
        int clientsNormaux,
        int clientsASurveiller,
        int clientsBloques,
        int dossiersATraiter,
        int demandesEnRetard,
        int cloturesARisque,
        int anomaliesFiscales,
        int rapprochementsCritiques,
        List<ClientHealthDto> clients,
        List<RisqueClientDto> topRisques
) {
}
