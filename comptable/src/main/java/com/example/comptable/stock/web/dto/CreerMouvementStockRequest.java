package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.TypeMouvementStock;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code quantite} est une grandeur strictement positive pour ENTREE/SORTIE. Pour AJUSTEMENT
 * elle porte le signe de l'ecart constate (positif = surplus, negatif = perte/casse) puisqu'un
 * ajustement n'a pas de sens de mouvement fixe contrairement a une entree ou une sortie.
 * {@code prixUnitaireHt} n'est requis que pour ENTREE (prix d'achat) ; ignore pour SORTIE/
 * AJUSTEMENT qui se valorisent toujours au PUMP en vigueur.
 */
public record CreerMouvementStockRequest(
        @NotNull UUID articleId,
        @NotNull TypeMouvementStock type,
        @NotNull BigDecimal quantite,
        BigDecimal prixUnitaireHt,
        LocalDate dateMouvement,
        String libelle
) {
}
