package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BilanDto(
        LocalDate dateArrete,
        SectionFinanciereDto actifImmobilise,
        SectionFinanciereDto actifCirculant,
        SectionFinanciereDto tresorerieActif,
        BigDecimal totalActif,
        SectionFinanciereDto ressourcesDurables,
        SectionFinanciereDto passifCirculant,
        SectionFinanciereDto tresoreriePassif,
        BigDecimal totalPassif
) {
}
