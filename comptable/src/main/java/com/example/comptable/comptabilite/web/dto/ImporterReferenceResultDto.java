package com.example.comptable.comptabilite.web.dto;

import java.util.List;

/** Resultat d'un import de referentiel : ce qui a ete effectivement ajoute, et ce qui a ete
 * ignore avec la raison (jamais un echec global sur une seule collision). */
public record ImporterReferenceResultDto(
        List<String> comptesAjoutes,
        List<CompteIgnoreDto> comptesIgnores
) {
}
