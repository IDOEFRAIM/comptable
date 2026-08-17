package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.MouvementStock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record MouvementStockDto(
        UUID id,
        UUID articleId,
        String articleCode,
        String type,
        BigDecimal quantite,
        BigDecimal prixUnitaireHt,
        BigDecimal valeurMouvement,
        BigDecimal quantiteApres,
        BigDecimal pumpApres,
        LocalDate dateMouvement,
        String libelle,
        UUID ecritureId
) {
    public static MouvementStockDto from(MouvementStock mouvement) {
        return new MouvementStockDto(
                mouvement.getId(), mouvement.getArticle().getId(), mouvement.getArticle().getCodeArticle(),
                mouvement.getType().name(), mouvement.getQuantite(), mouvement.getPrixUnitaireHt(),
                mouvement.getValeurMouvement(), mouvement.getQuantiteApres(), mouvement.getPumpApres(),
                mouvement.getDateMouvement(), mouvement.getLibelle(), mouvement.getEcritureId()
        );
    }
}
