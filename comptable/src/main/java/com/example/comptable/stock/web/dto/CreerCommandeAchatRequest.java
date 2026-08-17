package com.example.comptable.stock.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreerCommandeAchatRequest(
        @NotNull UUID fournisseurId,
        @NotNull LocalDate dateCommande,
        @NotEmpty @Valid List<LigneCommandeAchatRequest> lignes
) {
}
