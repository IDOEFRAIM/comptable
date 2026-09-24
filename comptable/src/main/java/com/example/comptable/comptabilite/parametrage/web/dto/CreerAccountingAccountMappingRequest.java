package com.example.comptable.comptabilite.parametrage.web.dto;

import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreerAccountingAccountMappingRequest(
        @NotNull AccountPurpose purpose,
        String contextKey,
        @NotNull UUID compteId,
        Integer priorite
) {
}
