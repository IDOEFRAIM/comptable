package com.example.comptable.finance.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Mission P1 (section 22) : toujours la source (compte) et la date, jamais un chiffre nu. */
public record CashPositionDto(
        LocalDate dateReference,
        BigDecimal total,
        List<PositionTresorerieDto> parCompte
) {
}
