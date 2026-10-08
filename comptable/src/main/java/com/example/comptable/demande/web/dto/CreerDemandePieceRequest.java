package com.example.comptable.demande.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/** @param envoyer {@code true} : la demande part aussitot au client (boite d'envoi) ; sinon elle est seulement enregistree. */
public record CreerDemandePieceRequest(
        @NotBlank String libelle,
        String description,
        LocalDate dateLimite,
        Boolean envoyer
) {
    public CreerDemandePieceRequest(String libelle, String description, LocalDate dateLimite) {
        this(libelle, description, dateLimite, null);
    }
}
