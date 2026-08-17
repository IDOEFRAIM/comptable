package com.example.comptable.agent.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Miroir exact de ai-service/app/schemas/matching.py::HistoricalPayment. */
public record HistoricalPaymentDTO(BigDecimal amount, LocalDate date, String method) {
}
