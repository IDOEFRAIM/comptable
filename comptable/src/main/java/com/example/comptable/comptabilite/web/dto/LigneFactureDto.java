package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.LigneFacture;

import java.math.BigDecimal;
import java.util.UUID;

public record LigneFactureDto(
        UUID id,
        String designation,
        BigDecimal quantite,
        BigDecimal prixUnitaire,
        BigDecimal tauxTva,
        BigDecimal montantHt,
        BigDecimal montantTtc,
        UUID articleId,
        UUID mouvementStockId
) {
    public static LigneFactureDto from(LigneFacture ligne) {
        return new LigneFactureDto(
                ligne.getId(), ligne.getDesignation(), ligne.getQuantite(), ligne.getPrixUnitaire(), ligne.getTauxTva(),
                ligne.montantHt(), ligne.montantTtc(), ligne.getArticleId(), ligne.getMouvementStockId()
        );
    }
}
