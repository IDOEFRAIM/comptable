package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CompteResultatDto(
        LocalDate dateDebut,
        LocalDate dateFin,
        SectionFinanciereDto chargesExploitation,
        SectionFinanciereDto produitsExploitation,
        BigDecimal resultatExploitation,
        SectionFinanciereDto chargesFinancieres,
        SectionFinanciereDto produitsFinanciers,
        BigDecimal resultatFinancier,
        BigDecimal resultatNet
) {
}
