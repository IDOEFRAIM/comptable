package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.ExerciceComptable;

import java.time.LocalDate;
import java.util.UUID;

public record ExerciceComptableDto(
        UUID id,
        LocalDate dateDebut,
        LocalDate dateFin,
        String statut
) {
    public static ExerciceComptableDto from(ExerciceComptable exercice) {
        return new ExerciceComptableDto(
                exercice.getId(),
                exercice.getDateDebut(),
                exercice.getDateFin(),
                exercice.getStatut().name()
        );
    }
}
