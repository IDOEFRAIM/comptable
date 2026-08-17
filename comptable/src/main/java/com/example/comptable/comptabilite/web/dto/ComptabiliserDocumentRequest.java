package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record ComptabiliserDocumentRequest(
        @NotNull UUID tiersId,
        @NotNull UUID compteChargeOuProduitId,
        String libelle,
        LocalDate dateEcriture
) {
}
