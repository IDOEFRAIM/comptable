package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record GrandLivreLigneDto(
        UUID id,
        LocalDate date,
        String journalCode,
        String numeroPiece,
        String libelle,
        BigDecimal debit,
        BigDecimal credit,
        String lettrage,
        SoldeDto soldeProgressif
) {
}
