package com.example.comptable.agent.imputation.web.dto;

import com.example.comptable.agent.imputation.domain.ImputationSuggestion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ImputationSuggestionDto(
        UUID id,
        UUID documentId,
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
    public static ImputationSuggestionDto from(ImputationSuggestion p) {
        return new ImputationSuggestionDto(
                p.getId(),
                p.getDocument().getId(),
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
