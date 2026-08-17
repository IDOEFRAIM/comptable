package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.FactureAvoir;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FactureAvoirDto(
        UUID id,
        String numero,
        UUID factureOrigineId,
        String factureOrigineNumero,
        UUID tiersId,
        String tiersNom,
        LocalDate dateEmission,
        String motif,
        boolean retourStock,
        String statut,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        UUID ecritureId,
        List<LigneFactureAvoirDto> lignes
) {
    public static FactureAvoirDto from(FactureAvoir avoir) {
        return new FactureAvoirDto(
                avoir.getId(), avoir.getNumero(), avoir.getFactureOrigine().getId(), avoir.getFactureOrigine().getNumero(),
                avoir.getTiers().getId(), avoir.getTiers().getNom(), avoir.getDateEmission(), avoir.getMotif(),
                avoir.isRetourStock(), avoir.getStatut().name(),
                avoir.montantHt(), avoir.montantTva(), avoir.montantTtc(), avoir.getEcritureId(),
                avoir.getLignes().stream().map(LigneFactureAvoirDto::from).toList()
        );
    }
}
