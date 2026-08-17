package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.Tiers;

import java.util.UUID;

public record TiersDto(
        UUID id,
        String type,
        String nom,
        String numeroContribuable,
        UUID compteComptableId,
        String compteComptableNumero
) {
    public static TiersDto from(Tiers tiers) {
        return new TiersDto(
                tiers.getId(),
                tiers.getType().name(),
                tiers.getNom(),
                tiers.getNumeroContribuable(),
                tiers.getCompteComptable().getId(),
                tiers.getCompteComptable().getNumero()
        );
    }
}
