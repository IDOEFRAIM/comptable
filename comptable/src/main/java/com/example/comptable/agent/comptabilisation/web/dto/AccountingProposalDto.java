package com.example.comptable.agent.comptabilisation.web.dto;

import com.example.comptable.agent.comptabilisation.domain.AccountingProposal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AccountingProposalDto(
        UUID id,
        UUID documentId,
        String journalCode,
        UUID tiersId,
        String tiersNom,
        UUID compteChargeProduitId,
        String compteChargeProduitNumero,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        double confidence,
        String status,
        List<String> reasons,
        OffsetDateTime createdAt,
        OffsetDateTime validatedAt
) {
    public static AccountingProposalDto from(AccountingProposal p) {
        return new AccountingProposalDto(
                p.getId(),
                p.getDocument().getId(),
                p.getJournal() != null ? p.getJournal().getCode() : null,
                p.getTiers() != null ? p.getTiers().getId() : null,
                p.getTiers() != null ? p.getTiers().getNom() : null,
                p.getCompteChargeProduit() != null ? p.getCompteChargeProduit().getId() : null,
                p.getCompteChargeProduit() != null ? p.getCompteChargeProduit().getNumero() : null,
                p.getMontantHt(), p.getMontantTva(), p.getMontantTtc(),
                p.getConfidence(), p.getStatus().name(), p.getReasonsJson(),
                p.getCreatedAt(), p.getValidatedAt()
        );
    }
}
