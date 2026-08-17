package com.example.comptable.finance.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PositionTresorerieDto(
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        String canal,
        BigDecimal solde
) {
}
