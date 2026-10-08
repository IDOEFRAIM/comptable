package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Rattache l'acquisition a la comptabilisation d'une facture d'achat deja passee (ex. DOCUMENT, FACTURE_FOURNISSEUR). */
public record LierAcquisitionRequest(@NotBlank String sourceType, @NotNull UUID sourceId) {
}
