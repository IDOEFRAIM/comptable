package com.example.comptable.entreprise.web.dto;

import com.example.comptable.entreprise.domain.Entreprise;

import java.util.UUID;

public record EntrepriseDto(
        UUID id,
        String raisonSociale,
        String pays,
        String devise,
        String statut
) {
    public static EntrepriseDto from(Entreprise entreprise) {
        return new EntrepriseDto(
                entreprise.getId(),
                entreprise.getRaisonSociale(),
                entreprise.getPays(),
                entreprise.getDevise(),
                entreprise.getStatut()
        );
    }
}
