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
        OffsetDateTime resolueAt,
        UUID ecritureId,
        String motif,
        String canal,
        java.math.BigDecimal montant,
        LocalDate dateOperation,
        String tiersNom,
        String periode,
        String descriptionOperation,
        OffsetDateTime sentAt,
        OffsetDateTime derniereRelanceAt,
        int nbRelances,
        OffsetDateTime prochaineRelanceAt,
        OffsetDateTime receivedAt,
        String motifBlocage,
        String resoluPar,
        DernierMessageDto dernierMessage
) {
    /** Dernier message sortant de la demande : statut reel du fournisseur (SENT = accepte, jamais « delivre »). */
    public record DernierMessageDto(UUID id, String statut, String type, int tentatives, OffsetDateTime envoyeLe, String derniereErreur,
                                    OffsetDateTime prochaineTentative) {
    }

    public DemandePieceDto avecDernierMessage(DernierMessageDto message) {
        return new DemandePieceDto(id, libelle, description, dateLimite, statut, documentId, findingId, demandeeParNom, createdAt, resolueAt,
                ecritureId, motif, canal, montant, dateOperation, tiersNom, periode, descriptionOperation, sentAt, derniereRelanceAt, nbRelances,
                prochaineRelanceAt, receivedAt, motifBlocage, resoluPar, message);
    }

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
                d.getResolueAt(),
                d.getEcritureId(),
                d.getMotif(),
                d.getCanal(),
                d.getMontant(),
                d.getDateOperation(),
                d.getTiersNom(),
                d.getPeriode(),
                d.getDescriptionOperation(),
                d.getSentAt(),
                d.getDerniereRelanceAt(),
                d.getNbRelances(),
                d.getProchaineRelanceAt(),
                d.getReceivedAt(),
                d.getMotifBlocage(),
                d.getResoluPar(),
                null
        );
    }
}
