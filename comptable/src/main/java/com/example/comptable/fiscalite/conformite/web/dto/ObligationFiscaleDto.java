package com.example.comptable.fiscalite.conformite.web.dto;

import com.example.comptable.fiscalite.domain.DeclarationFiscale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ObligationFiscaleDto(
        UUID id,
        String typeImpot,
        String periode,
        LocalDate dateEcheance,
        String statut,
        BigDecimal montant,
        String document
) {
    public static ObligationFiscaleDto from(DeclarationFiscale d) {
        return new ObligationFiscaleDto(d.getId(), d.getTypeImpot().name(), d.getPeriode(), d.getDateEcheance(),
                d.getStatut().name(), d.getMontant(), d.getPieceJointeUrl());
    }
}
