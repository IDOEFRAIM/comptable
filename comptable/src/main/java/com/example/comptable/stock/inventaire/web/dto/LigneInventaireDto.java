package com.example.comptable.stock.inventaire.web.dto;

import com.example.comptable.stock.inventaire.domain.LigneInventaire;

import java.math.BigDecimal;
import java.util.UUID;

public record LigneInventaireDto(
        UUID id,
        UUID articleId,
        String articleCode,
        String articleDesignation,
        BigDecimal stockTheorique,
        BigDecimal stockCompte,
        BigDecimal ecart,
        BigDecimal coutUnitaire,
        BigDecimal valeurEcart
) {
    public static LigneInventaireDto from(LigneInventaire ligne) {
        return new LigneInventaireDto(
                ligne.getId(), ligne.getArticle().getId(), ligne.getArticle().getCodeArticle(),
                ligne.getArticle().getDesignation(), ligne.getStockTheorique(), ligne.getStockCompte(),
                ligne.getEcart(), ligne.getCoutUnitaire(), ligne.getValeurEcart()
        );
    }
}
