package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;

public record LigneAmortissementDto(int annee, BigDecimal dotation, BigDecimal valeurNetteComptableFin, boolean genere) {
}
