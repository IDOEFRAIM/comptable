package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;

public record PosteFinancierDto(String numero, String intitule, BigDecimal montant) {
}
