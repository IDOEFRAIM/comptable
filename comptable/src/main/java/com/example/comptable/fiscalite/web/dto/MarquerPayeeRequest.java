package com.example.comptable.fiscalite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MarquerPayeeRequest(LocalDate datePaiement, BigDecimal montant) {
}
