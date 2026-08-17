package com.example.comptable.comptabilite.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GrandLivreDto(
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        LocalDate dateDebut,
        LocalDate dateFin,
        SoldeDto soldeOuverture,
        List<GrandLivreLigneDto> lignes,
        SoldeDto soldeCloture
) {
}
