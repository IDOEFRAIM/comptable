package com.example.comptable.comptabilite.provision.web.dto;

import com.example.comptable.comptabilite.provision.domain.TypeMouvementProvision;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreerProvisionRequest(
        @NotNull UUID exerciceId,
        @NotNull String nature,
        String objet,
        BigDecimal montantPrecedent,
        @NotNull BigDecimal montantNouveau,
        String justification,
        @NotNull TypeMouvementProvision typeMouvement,
        @NotNull UUID compteDotationId,
        @NotNull UUID compteProvisionId,
        @NotNull UUID compteRepriseId
) {
}
