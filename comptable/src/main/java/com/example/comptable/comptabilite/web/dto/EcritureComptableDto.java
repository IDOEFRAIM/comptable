package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.EcritureComptable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record EcritureComptableDto(
        UUID id,
        UUID entrepriseId,
        String journalCode,
        String numeroPiece,
        LocalDate dateEcriture,
        String libelle,
        String statut,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        List<LigneEcritureDto> lignes,
        OffsetDateTime createdAt
) {
    public static EcritureComptableDto from(EcritureComptable ecriture) {
        return new EcritureComptableDto(
                ecriture.getId(),
                ecriture.getEntreprise().getId(),
                ecriture.getJournal().getCode(),
                ecriture.getNumeroPiece(),
                ecriture.getDateEcriture(),
                ecriture.getLibelle(),
                ecriture.getStatut().name(),
                ecriture.totalDebit(),
                ecriture.totalCredit(),
                ecriture.getLignes().stream().map(LigneEcritureDto::from).toList(),
                ecriture.getCreatedAt()
        );
    }
}
