package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record SectionFinanciereDto(String libelle, List<PosteFinancierDto> postes, BigDecimal total) {
}
