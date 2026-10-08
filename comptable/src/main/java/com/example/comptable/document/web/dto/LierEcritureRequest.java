package com.example.comptable.document.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record LierEcritureRequest(@NotNull UUID ecritureId) {
}
