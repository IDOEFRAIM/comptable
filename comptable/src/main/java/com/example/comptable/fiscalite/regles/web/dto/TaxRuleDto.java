package com.example.comptable.fiscalite.regles.web.dto;

import com.example.comptable.fiscalite.regles.domain.TaxRule;

import java.time.LocalDate;
import java.util.UUID;

public record TaxRuleDto(
        UUID id,
        String pays,
        String regime,
        String code,
        String libelle,
        String description,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String source
) {
    public static TaxRuleDto from(TaxRule r) {
        return new TaxRuleDto(r.getId(), r.getPays(), r.getRegime(), r.getCode(), r.getLibelle(),
                r.getDescription(), r.getEffectiveFrom(), r.getEffectiveTo(), r.getSource());
    }
}
