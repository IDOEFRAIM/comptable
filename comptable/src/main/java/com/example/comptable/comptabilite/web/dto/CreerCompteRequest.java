package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Ajout d'un compte general personnalise, hors plan SYSCOHADA (celui-ci reste clone en lecture
 * seule a la creation de l'entreprise, cf. {@code EntrepriseService.clonerPlanComptable}) - un
 * cabinet garde besoin d'un compte non prevu par le referentiel (ex: subdivision analytique).
 * Reserve aux roles COLLABORATEUR/EXPERT_COMPTABLE (cf. SecurityConfig).
 */
public record CreerCompteRequest(
        @NotBlank String numero,
        @NotBlank String intitule,
        boolean lettrable
) {
}
