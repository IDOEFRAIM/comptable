package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record GenererDotationsResultDto(UUID ecritureId, int annee, BigDecimal totalDotations, int nombreImmobilisations) {
}
