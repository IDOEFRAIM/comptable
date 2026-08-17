package com.example.comptable.fiscalite.web.dto;

import jakarta.validation.constraints.NotNull;

public record GenererEcheancierRequest(@NotNull Integer annee) {
}
