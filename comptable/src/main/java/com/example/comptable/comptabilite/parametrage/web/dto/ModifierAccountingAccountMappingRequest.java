package com.example.comptable.comptabilite.parametrage.web.dto;

import java.util.UUID;

public record ModifierAccountingAccountMappingRequest(
        UUID compteId,
        String contextKey,
        Boolean actif
) {
}
