package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BalanceAgeeDto(
        LocalDate dateArrete,
        List<BalanceAgeeLigneDto> lignes,
        BigDecimal totalGeneral
) {
}
