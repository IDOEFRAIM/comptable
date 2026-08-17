package com.example.comptable.demande.web.dto;

import com.example.comptable.demande.domain.DemandePiece;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DemandePieceDto(
        UUID id,
        String libelle,
        String description,
        LocalDate dateLimite,
        String statut,
        UUID documentId,
        UUID findingId,
        String demandeeParNom,
        OffsetDateTime createdAt,
        OffsetDateTime resolueAt
) {
    public static DemandePieceDto from(DemandePiece d) {
        String demandeur = d.getDemandeePar() != null
                ? d.getDemandeePar().getPrenom() + " " + d.getDemandeePar().getNom()
                : null;
        return new DemandePieceDto(
                d.getId(),
                d.getLibelle(),
                d.getDescription(),
                d.getDateLimite(),
                d.getStatut().name(),
                d.getDocumentId(),
                d.getFindingId(),
                demandeur,
                d.getCreatedAt(),
                d.getResolueAt()
        );
    }
}
