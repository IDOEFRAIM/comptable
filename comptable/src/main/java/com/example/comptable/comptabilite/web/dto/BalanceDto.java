package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BalanceDto(
        LocalDate dateDebut,
        LocalDate dateFin,
        List<BalanceLigneDto> lignes,
        BigDecimal totalSoldeOuvertureDebit,
        BigDecimal totalSoldeOuvertureCredit,
        BigDecimal totalMouvementDebit,
        BigDecimal totalMouvementCredit,
        BigDecimal totalSoldeClotureDebit,
        BigDecimal totalSoldeClotureCredit
) {
}
