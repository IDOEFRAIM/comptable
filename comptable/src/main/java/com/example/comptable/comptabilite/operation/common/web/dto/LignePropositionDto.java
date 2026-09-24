package com.example.comptable.comptabilite.operation.common.web.dto;

import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;

import java.math.BigDecimal;
import java.util.UUID;

public record LignePropositionDto(
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        UUID tiersId,
        String libelle,
        BigDecimal debit,
        BigDecimal credit
) {
    public static LignePropositionDto from(AccountingOperationProposalLigne ligne) {
        return new LignePropositionDto(
                ligne.getCompte().getId(),
                ligne.getCompte().getNumero(),
                ligne.getCompte().getIntitule(),
                ligne.getTiers() != null ? ligne.getTiers().getId() : null,
                ligne.getLibelle(),
                ligne.getDebit(),
                ligne.getCredit()
        );
    }
}
