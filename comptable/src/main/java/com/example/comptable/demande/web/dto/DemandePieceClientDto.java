package com.example.comptable.demande.web.dto;

import com.example.comptable.demande.domain.DemandePiece;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ce que le CLIENT (le dirigeant) voit d'une demande : de quoi fournir sa piece et suivre sa demande. Jamais d'identifiant de
 * constat ou d'ecriture, de code technique, de motif interne ni d'etat d'envoi.
 *
 * @param etat {@code A_FOURNIR}, {@code RECUE} ou {@code CLOTUREE}
 */
public record DemandePieceClientDto(UUID id, String description, BigDecimal montant, LocalDate date, LocalDate dateLimite, String etat,
                                    OffsetDateTime demandeeLe) {

    public static DemandePieceClientDto from(DemandePiece d) {
        String etat = switch (d.getStatut()) {
            case A_ENVOYER, EN_ATTENTE, ECHEC_ENVOI -> "A_FOURNIR";
            case RECU -> "RECUE";
            default -> "CLOTUREE";
        };
        String description = d.getDescriptionOperation() != null ? d.getDescriptionOperation() : d.getLibelle();
        return new DemandePieceClientDto(d.getId(), description, d.getMontant(), d.getDateOperation(), d.getDateLimite(), etat, d.getCreatedAt());
    }

    /** Meme projection depuis le DTO complet (reponse a un depot) : on ne renvoie jamais au client plus que sa liste. */
    public static DemandePieceClientDto de(DemandePieceDto d) {
        String etat = switch (d.statut()) {
            case "A_ENVOYER", "EN_ATTENTE", "ECHEC_ENVOI" -> "A_FOURNIR";
            case "RECU" -> "RECUE";
            default -> "CLOTUREE";
        };
        String description = d.descriptionOperation() != null ? d.descriptionOperation() : d.libelle();
        return new DemandePieceClientDto(d.id(), description, d.montant(), d.dateOperation(), d.dateLimite(), etat, d.createdAt());
    }
}
