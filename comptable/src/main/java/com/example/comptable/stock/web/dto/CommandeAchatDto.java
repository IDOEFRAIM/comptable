package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.CommandeAchat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CommandeAchatDto(
        UUID id,
        String numero,
        UUID fournisseurId,
        String fournisseurNom,
        LocalDate dateCommande,
        String statut,
        LocalDate dateReception,
        BigDecimal montantTotalHt,
        List<LigneCommandeAchatDto> lignes
) {
    public static CommandeAchatDto from(CommandeAchat commande) {
        List<LigneCommandeAchatDto> lignes = commande.getLignes().stream().map(LigneCommandeAchatDto::from).toList();
        BigDecimal montantTotalHt = lignes.stream().map(LigneCommandeAchatDto::montantHt).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CommandeAchatDto(
                commande.getId(), commande.getNumero(), commande.getFournisseur().getId(), commande.getFournisseur().getNom(),
                commande.getDateCommande(), commande.getStatut().name(), commande.getDateReception(), montantTotalHt, lignes
        );
    }
}
