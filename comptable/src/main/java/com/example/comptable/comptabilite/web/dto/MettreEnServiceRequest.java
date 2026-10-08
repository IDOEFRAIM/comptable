package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record MettreEnServiceRequest(@NotNull LocalDate dateMiseEnService) {
}
