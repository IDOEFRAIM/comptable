package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.Article;

import java.math.BigDecimal;
import java.util.UUID;

public record ArticleDto(
        UUID id,
        String codeArticle,
        String designation,
        String categorie,
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
        String compteVariationNumero
) {
    public static ArticleDto from(Article article) {
        return new ArticleDto(
                article.getId(), article.getCodeArticle(), article.getDesignation(), article.getCategorie(),
                article.getUniteMesure(), article.getPrixVenteHt(), article.getTauxTva(),
                article.getQuantiteEnStock(), article.getValeurStockTotale(), article.getPump(),
                article.getCompteStock().getId(), article.getCompteStock().getNumero(),
                article.getCompteAchat().getId(), article.getCompteAchat().getNumero(),
                article.getCompteVente().getId(), article.getCompteVente().getNumero(),
                article.getCompteVariation().getId(), article.getCompteVariation().getNumero()
        );
    }
}
