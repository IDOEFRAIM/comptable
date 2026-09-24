package com.example.comptable.comptabilite.parametrage.web.dto;

import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record TestResolutionRequest(
        @NotNull AccountPurpose purpose,
        String natureOperation,
        String contextKey,
        Map<String, String> metadata
) {
}
