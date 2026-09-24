package com.example.comptable.stock.web.dto;

import com.example.comptable.comptabilite.operation.common.web.dto.LignePropositionDto;
import com.example.comptable.stock.domain.AvoirFournisseur;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AvoirFournisseurDto(
        UUID id,
        UUID factureId,
        String factureNumero,
        UUID fournisseurId,
        String fournisseurNom,
        String numero,
        LocalDate dateEmission,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        String motif,
        boolean retourStock,
        UUID ecritureId,
        List<LignePropositionDto> lignesEcriture
) {
    public static AvoirFournisseurDto from(AvoirFournisseur avoir) {
        return from(avoir, List.of());
    }

    public static AvoirFournisseurDto from(AvoirFournisseur avoir, List<LignePropositionDto> lignesEcriture) {
        return new AvoirFournisseurDto(
                avoir.getId(), avoir.getFacture().getId(), avoir.getFacture().getNumero(),
                avoir.getFournisseur().getId(), avoir.getFournisseur().getNom(),
                avoir.getNumero(), avoir.getDateEmission(),
                avoir.getMontantHt(), avoir.getMontantTva(), avoir.getMontantTtc(),
                avoir.getMotif(), avoir.isRetourStock(), avoir.getEcritureId(), lignesEcriture
        );
    }
}
