package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.Depot;

import java.util.UUID;

public record DepotDto(UUID id, String code, String libelle, boolean actif) {
    public static DepotDto from(Depot depot) {
        return new DepotDto(depot.getId(), depot.getCode(), depot.getLibelle(), depot.isActif());
    }
}
