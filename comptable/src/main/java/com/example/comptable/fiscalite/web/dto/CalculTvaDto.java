package com.example.comptable.fiscalite.web.dto;

import java.math.BigDecimal;

/**
 * Detail du calcul automatique de la TVA a partir des ecritures validees d'une periode :
 * montantAPayer = montantCollecte (compte 443, credite sur les ventes) - montantDeductible
 * (compte 4456, debite sur les achats). Peut etre negatif (credit de TVA).
 */
public record CalculTvaDto(
        String periode,
        BigDecimal montantCollecte,
        BigDecimal montantDeductible,
        BigDecimal montantAPayer
) {
}
