package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.FactureFournisseur;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record FactureFournisseurDto(
        UUID id,
        UUID commandeId,
        String commandeNumero,
        UUID fournisseurId,
        String fournisseurNom,
        String numero,
        LocalDate dateFacture,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        UUID ecritureId
) {
    public static FactureFournisseurDto from(FactureFournisseur facture) {
        return new FactureFournisseurDto(
                facture.getId(), facture.getCommande().getId(), facture.getCommande().getNumero(),
                facture.getFournisseur().getId(), facture.getFournisseur().getNom(),
                facture.getNumero(), facture.getDateFacture(),
                facture.getMontantHt(), facture.getMontantTva(), facture.getMontantTtc(), facture.getEcritureId()
        );
    }
}
