package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.ModeGestionStock;
import com.example.comptable.stock.domain.PolitiqueStockNegatif;

import java.math.BigDecimal;

/** Edition d'un article : jamais le code, les comptes ou la valorisation en cours (quantite/
 * valeur/PUMP restent exclusivement pilotes par les mouvements de stock). Champs nullable = non
 * modifie. */
public record ModifierArticleRequest(
        String designation,
        String categorie,
        BigDecimal prixVenteHt,
        BigDecimal tauxTva,
        Boolean actif,
        BigDecimal stockMinimum,
        PolitiqueStockNegatif politiqueStockNegatif,
        ModeGestionStock modeGestionStock
) {
}
