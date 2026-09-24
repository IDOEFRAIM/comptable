package com.example.comptable.comptabilite.depreciation.web.dto;

import com.example.comptable.comptabilite.depreciation.domain.Depreciation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DepreciationDto(
        UUID id,
        UUID exerciceId,
        String objetType,
        UUID objetId,
        BigDecimal valeurComptable,
        BigDecimal valeurRecouvrable,
        BigDecimal perte,
        String justification,
        UUID compteDotationId,
        UUID compteDepreciationId,
        String statut,
        UUID propositionId,
        UUID ecritureId,
        OffsetDateTime createdAt
) {
    public static DepreciationDto from(Depreciation depreciation) {
        BigDecimal perte = depreciation.getValeurComptable().subtract(depreciation.getValeurRecouvrable());
        if (perte.signum() < 0) {
            perte = BigDecimal.ZERO;
        }
        return new DepreciationDto(
                depreciation.getId(),
                depreciation.getExercice().getId(),
                depreciation.getObjetType().name(),
                depreciation.getObjetId(),
                depreciation.getValeurComptable(),
                depreciation.getValeurRecouvrable(),
                perte,
                depreciation.getJustification(),
                depreciation.getCompteDotation().getId(),
                depreciation.getCompteDepreciation().getId(),
                depreciation.getStatut().name(),
                depreciation.getProposition() != null ? depreciation.getProposition().getId() : null,
                depreciation.getProposition() != null && depreciation.getProposition().getEcriture() != null
                        ? depreciation.getProposition().getEcriture().getId() : null,
                depreciation.getCreatedAt()
        );
    }
}
