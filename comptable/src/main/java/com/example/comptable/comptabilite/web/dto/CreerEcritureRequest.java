package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreerEcritureRequest(
        @NotNull UUID journalId,
        @NotNull LocalDate dateEcriture,
        String libelle,
        String numeroPiece,
        @NotEmpty @Valid List<LigneEcritureRequest> lignes
) {
}
