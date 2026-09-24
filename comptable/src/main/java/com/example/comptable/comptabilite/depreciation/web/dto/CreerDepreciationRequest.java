package com.example.comptable.comptabilite.depreciation.web.dto;

import com.example.comptable.comptabilite.depreciation.domain.ObjetDepreciation;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreerDepreciationRequest(
        @NotNull UUID exerciceId,
        @NotNull ObjetDepreciation objetType,
        @NotNull UUID objetId,
        @NotNull BigDecimal valeurComptable,
        @NotNull BigDecimal valeurRecouvrable,
        String justification,
        @NotNull UUID compteDotationId,
        @NotNull UUID compteDepreciationId
) {
}
