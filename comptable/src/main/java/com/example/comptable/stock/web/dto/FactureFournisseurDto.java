package com.example.comptable.stock.web.dto;

import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.common.web.dto.LignePropositionDto;
import com.example.comptable.stock.domain.FactureFournisseur;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
        UUID ecritureId,
        List<LignePropositionDto> lignesEcriture
) {
    public static FactureFournisseurDto from(FactureFournisseur facture) {
        return from(facture, null);
    }

    /** {@code proposition} porte les comptes/montants reels resolus par AchatRule - affiches tels
     * quels cote frontend plutot qu'un libelle de comptes fige (voir achats.component.html). */
    public static FactureFournisseurDto from(FactureFournisseur facture, AccountingOperationProposalDto proposition) {
        return new FactureFournisseurDto(
                facture.getId(), facture.getCommande().getId(), facture.getCommande().getNumero(),
                facture.getFournisseur().getId(), facture.getFournisseur().getNom(),
                facture.getNumero(), facture.getDateFacture(),
                facture.getMontantHt(), facture.getMontantTva(), facture.getMontantTtc(), facture.getEcritureId(),
                proposition != null ? proposition.lignes() : List.of()
        );
    }
}
