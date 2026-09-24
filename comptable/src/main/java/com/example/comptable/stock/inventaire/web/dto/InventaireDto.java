package com.example.comptable.stock.inventaire.web.dto;

import com.example.comptable.stock.inventaire.domain.Inventaire;
import com.example.comptable.stock.inventaire.domain.LigneInventaire;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InventaireDto(
        UUID id,
        LocalDate date,
        String statut,
        String commentaire,
        int nbLignes,
        BigDecimal valeurEcartTotal,
        List<LigneInventaireDto> lignes,
        UUID propositionId,
        UUID ecritureId,
        OffsetDateTime createdAt
) {
    public static InventaireDto from(Inventaire inventaire) {
        BigDecimal valeurEcartTotal = inventaire.getLignes().stream()
                .map(LigneInventaire::getValeurEcart)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InventaireDto(
                inventaire.getId(), inventaire.getDate(), inventaire.getStatut().name(), inventaire.getCommentaire(),
                inventaire.getLignes().size(), valeurEcartTotal,
                inventaire.getLignes().stream().map(LigneInventaireDto::from).toList(),
                inventaire.getProposition() != null ? inventaire.getProposition().getId() : null,
                inventaire.getProposition() != null && inventaire.getProposition().getEcriture() != null
                        ? inventaire.getProposition().getEcriture().getId() : null,
                inventaire.getCreatedAt()
        );
    }
}
