package com.example.comptable.stock.inventaire.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreerInventaireRequest(@NotNull LocalDate date, String commentaire) {
}
