package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** Creation d'une subdivision locale du PCE - distinct de {@code CreerCompteRequest} (route
 * legacy) pour ne pas changer le contrat de cette derniere. {@code parentId} optionnel : null
 * pour un compte de premier niveau, sinon doit designer un compte de la meme entreprise. */
public record CreerSubdivisionCompteRequest(
        @NotBlank String numero,
        @NotBlank String intitule,
        boolean lettrable,
        UUID parentId
) {
}
