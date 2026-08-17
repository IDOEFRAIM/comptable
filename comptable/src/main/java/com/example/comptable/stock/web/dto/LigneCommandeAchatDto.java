package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.LigneCommandeAchat;

import java.math.BigDecimal;
import java.util.UUID;

public record LigneCommandeAchatDto(
        UUID id,
        UUID articleId,
        String articleCode,
        String articleDesignation,
        BigDecimal quantite,
        BigDecimal prixUnitaireHt,
        BigDecimal montantHt
) {
    public static LigneCommandeAchatDto from(LigneCommandeAchat ligne) {
        return new LigneCommandeAchatDto(
                ligne.getId(), ligne.getArticle().getId(), ligne.getArticle().getCodeArticle(),
                ligne.getArticle().getDesignation(), ligne.getQuantite(), ligne.getPrixUnitaireHt(),
                ligne.getQuantite().multiply(ligne.getPrixUnitaireHt())
        );
    }
}
