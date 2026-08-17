package com.example.comptable.finance.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Mission P1 (section 20) : vue financiere synthetique pour une periode - entierement calculee
 * a partir de moteurs deja existants en P0 (solde tresorerie, balance agee, mouvements de
 * comptes), jamais persistee. */
public record FinancialSnapshotDto(
        LocalDate periodeDebut,
        LocalDate periodeFin,
        BigDecimal cash,
        BigDecimal receivables,
        BigDecimal payables,
        BigDecimal revenue,
        BigDecimal expenses,
        BigDecimal profit,
        BigDecimal taxes
) {
}
