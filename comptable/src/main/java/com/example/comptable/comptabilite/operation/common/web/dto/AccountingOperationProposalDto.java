package com.example.comptable.comptabilite.operation.common.web.dto;

import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AccountingOperationProposalDto(
        UUID id,
        UUID entrepriseId,
        String type,
        String statut,
        UUID journalId,
        String journalCode,
        UUID tiersId,
        LocalDate dateOperation,
        String libelle,
        String reference,
        List<LignePropositionDto> lignes,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean equilibree,
        List<String> explications,
        List<String> warnings,
        OffsetDateTime createdAt,
        OffsetDateTime validatedAt,
        String validatedByNom,
        String rejectionReason,
        UUID ecritureId,
        String sourceType,
        UUID sourceId
) {
    public static AccountingOperationProposalDto from(AccountingOperationProposal proposal) {
        return new AccountingOperationProposalDto(
                proposal.getId(),
                proposal.getEntreprise().getId(),
                proposal.getType().name(),
                proposal.getStatut().name(),
                proposal.getJournal() != null ? proposal.getJournal().getId() : null,
                proposal.getJournal() != null ? proposal.getJournal().getCode() : null,
                proposal.getTiers() != null ? proposal.getTiers().getId() : null,
                proposal.getDateOperation(),
                proposal.getLibelle(),
                proposal.getReference(),
                proposal.getLignes().stream().map(LignePropositionDto::from).toList(),
                proposal.getMontantTotalDebit(),
                proposal.getMontantTotalCredit(),
                proposal.isEquilibree(),
                proposal.getExplicationsJson(),
                proposal.getWarningsJson(),
                proposal.getCreatedAt(),
                proposal.getValidatedAt(),
                proposal.getValidatedBy() != null
                        ? (proposal.getValidatedBy().getPrenom() + " " + proposal.getValidatedBy().getNom())
                        : null,
                proposal.getRejectionReason(),
                proposal.getEcriture() != null ? proposal.getEcriture().getId() : null,
                proposal.getSourceType(),
                proposal.getSourceId()
        );
    }
}
