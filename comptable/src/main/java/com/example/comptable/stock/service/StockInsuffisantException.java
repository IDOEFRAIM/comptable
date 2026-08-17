package com.example.comptable.stock.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/**
 * Levee quand une sortie (vente, ajustement de perte) demande une quantite superieure au stock
 * disponible pour un article. Type dedie plutot qu'une {@link ResponseStatusException} generique
 * pour que les appelants (ex. FacturationService) puissent la distinguer explicitement des autres
 * echecs de validation lors de la generation automatique du destockage.
 */
public class StockInsuffisantException extends ResponseStatusException {

    public StockInsuffisantException(String articleCode, BigDecimal disponible, BigDecimal demande) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "Stock insuffisant pour %s : %s disponible, %s demande".formatted(articleCode, disponible, demande));
    }
}
