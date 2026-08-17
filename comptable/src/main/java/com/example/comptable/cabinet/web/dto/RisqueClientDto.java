package com.example.comptable.cabinet.web.dto;

import java.util.UUID;

/** Mission P1 (section 10) : un item du "Top risques" du portefeuille - toujours trace jusqu'a un
 * {@code Finding} reel (sourceType/sourceId, disponibles via le Finding d'origine si besoin), jamais
 * un score opaque. */
public record RisqueClientDto(
        UUID entrepriseId,
        String raisonSociale,
        String severite,
        String cause,
        String depuisQuand,
        String actionRecommandee
) {
}
