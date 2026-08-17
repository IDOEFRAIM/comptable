package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.LigneFactureAvoir;

import java.math.BigDecimal;
import java.util.UUID;

public record LigneFactureAvoirDto(
        UUID id,
        UUID ligneFactureOrigineId,
        String designation,
        BigDecimal quantite,
        BigDecimal prixUnitaire,
        BigDecimal tauxTva,
        BigDecimal montantHt,
        BigDecimal montantTtc
) {
    public static LigneFactureAvoirDto from(LigneFactureAvoir ligne) {
        return new LigneFactureAvoirDto(
                ligne.getId(), ligne.getLigneFactureOrigine().getId(), ligne.getDesignation(),
                ligne.getQuantite(), ligne.getPrixUnitaire(), ligne.getTauxTva(),
                ligne.montantHt(), ligne.montantTtc()
        );
    }
}
