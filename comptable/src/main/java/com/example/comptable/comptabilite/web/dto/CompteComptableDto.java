package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.CompteComptable;

import java.util.UUID;

public record CompteComptableDto(
        UUID id,
        String numero,
        String intitule,
        String nature,
        boolean lettrable
) {
    public static CompteComptableDto from(CompteComptable compte) {
        return new CompteComptableDto(
                compte.getId(),
                compte.getNumero(),
                compte.getIntitule(),
                compte.getNature() != null ? compte.getNature().name() : null,
                compte.isLettrable()
        );
    }
}
