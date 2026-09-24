package com.example.comptable.comptabilite.parametrage.web.dto;

import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.domain.AccountingAccountMapping;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountingAccountMappingDto(
        UUID id,
        AccountPurpose purpose,
        String contextKey,
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        boolean actif,
        int priorite,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static AccountingAccountMappingDto from(AccountingAccountMapping mapping) {
        return new AccountingAccountMappingDto(
                mapping.getId(), mapping.getPurpose(), mapping.getContextKey(),
                mapping.getCompte().getId(), mapping.getCompte().getNumero(), mapping.getCompte().getIntitule(),
                mapping.isActif(), mapping.getPriorite(), mapping.getCreatedAt(), mapping.getUpdatedAt()
        );
    }
}
