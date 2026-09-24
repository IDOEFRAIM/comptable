package com.example.comptable.comptabilite.operation.common.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RejeterPropositionRequest(@NotBlank String motif) {
}
