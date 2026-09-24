package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.ModeGestionStock;
import com.example.comptable.stock.domain.PolitiqueStockNegatif;
import com.example.comptable.stock.domain.TypeArticle;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ArticleDto(
        UUID id,
        String codeArticle,
        String designation,
        String categorie,
        TypeArticle type,
        String uniteMesure,
        BigDecimal prixVenteHt,
        BigDecimal tauxTva,
        BigDecimal quantiteEnStock,
        BigDecimal valeurStockTotale,
        BigDecimal pump,
        UUID compteStockId,
        String compteStockNumero,
        UUID compteAchatId,
        String compteAchatNumero,
        UUID compteVenteId,
        String compteVenteNumero,
        UUID compteVariationId,
        String compteVariationNumero,
        boolean actif,
        BigDecimal stockMinimum,
        PolitiqueStockNegatif politiqueStockNegatif,
        ModeGestionStock modeGestionStock,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ArticleDto from(Article article) {
        return new ArticleDto(
                article.getId(), article.getCodeArticle(), article.getDesignation(), article.getCategorie(),
                article.getType(), article.getUniteMesure(), article.getPrixVenteHt(), article.getTauxTva(),
                article.getQuantiteEnStock(), article.getValeurStockTotale(), article.getPump(),
                article.getCompteStock().getId(), article.getCompteStock().getNumero(),
                article.getCompteAchat().getId(), article.getCompteAchat().getNumero(),
                article.getCompteVente().getId(), article.getCompteVente().getNumero(),
                article.getCompteVariation().getId(), article.getCompteVariation().getNumero(),
                article.isActif(), article.getStockMinimum(), article.getPolitiqueStockNegatif(),
                article.getModeGestionStock(), article.getCreatedAt(), article.getUpdatedAt()
        );
    }
}
