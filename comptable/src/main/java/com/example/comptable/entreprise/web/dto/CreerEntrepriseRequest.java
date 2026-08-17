package com.example.comptable.entreprise.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreerEntrepriseRequest(
        @NotBlank String raisonSociale,
        String numeroContribuable,
        String rccm,
        String formeJuridique,
        String regimeFiscal,
        String secteurActivite,
        @NotBlank @Size(min = 2, max = 2) String pays,
        @Size(min = 3, max = 3) String devise
) {
}
