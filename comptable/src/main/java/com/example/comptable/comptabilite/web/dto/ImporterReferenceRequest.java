package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.ModeImportReference;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Import (idempotent) d'une partie du referentiel SYSCOHADA dans le PCE d'une entreprise.
 * {@code codes} est ignore en mode TOUT ; en mode SELECTION, les ancetres des codes donnes sont
 * automatiquement inclus.
 */
public record ImporterReferenceRequest(
        @NotNull ModeImportReference mode,
        List<String> codes
) {
}
