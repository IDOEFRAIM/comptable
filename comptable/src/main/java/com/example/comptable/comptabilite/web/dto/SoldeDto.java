package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;

/** Represente un solde comptable : toujours un seul cote (debiteur ou crediteur) non nul. */
public record SoldeDto(BigDecimal debit, BigDecimal credit) {

    public static SoldeDto depuisNet(BigDecimal net) {
        return net.signum() >= 0
                ? new SoldeDto(net, BigDecimal.ZERO)
                : new SoldeDto(BigDecimal.ZERO, net.negate());
    }
}
