package com.example.comptable.fiscalite.web.dto;

import com.example.comptable.fiscalite.domain.DeclarationFiscale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DeclarationFiscaleDto(
        UUID id,
        String typeImpot,
        String periode,
        LocalDate dateEcheance,
        String statut,
        boolean enRetard,
        BigDecimal montant,
        LocalDate dateDeclaration,
        LocalDate datePaiement
) {
    public static DeclarationFiscaleDto from(DeclarationFiscale d) {
        return new DeclarationFiscaleDto(
                d.getId(),
                d.getTypeImpot().name(),
                d.getPeriode(),
                d.getDateEcheance(),
                d.getStatut().name(),
                d.estEnRetard(),
                d.getMontant(),
                d.getDateDeclaration(),
                d.getDatePaiement()
        );
    }
}
