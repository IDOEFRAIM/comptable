package com.example.comptable.comptabilite.balance;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Solde canonique d'un compte sur une periode, dans une vue donnee. Les soldes d'ouverture et de cloture sont des soldes NETS presentes en debit ou en
 * credit (jamais les deux) ; les mouvements sont les cumuls de la periode. Invariant : ouverture + mouvements = cloture.
 */
public record AccountBalance(UUID accountId, String accountNumber, String accountLabel,
                             BigDecimal openingDebit, BigDecimal openingCredit,
                             BigDecimal periodDebit, BigDecimal periodCredit,
                             BigDecimal closingDebit, BigDecimal closingCredit,
                             BigDecimal netBalance) {

    public static AccountBalance of(UUID accountId, String number, String label, BigDecimal openingNet,
                                    BigDecimal periodDebit, BigDecimal periodCredit) {
        BigDecimal closingNet = openingNet.add(periodDebit).subtract(periodCredit);
        return new AccountBalance(accountId, number, label,
                openingNet.max(BigDecimal.ZERO), openingNet.negate().max(BigDecimal.ZERO),
                periodDebit, periodCredit,
                closingNet.max(BigDecimal.ZERO), closingNet.negate().max(BigDecimal.ZERO),
                closingNet);
    }

    /** Solde d'ouverture net (debit - credit). */
    public BigDecimal openingNet() {
        return openingDebit.subtract(openingCredit);
    }

    /** Mouvement net de la periode (debit - credit). */
    public BigDecimal periodNet() {
        return periodDebit.subtract(periodCredit);
    }
}
