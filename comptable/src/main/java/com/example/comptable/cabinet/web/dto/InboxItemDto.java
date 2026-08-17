package com.example.comptable.cabinet.web.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mission P1 (section 12) : forme unique pour presenter cote a cote des {@code Finding} et des
 * {@code DemandePiece} EN_ATTENTE dans une seule Inbox de production - une projection de lecture,
 * jamais une nouvelle table (les deux entites d'origine restent les seules sources de verite).
 * Les items {@code DEMANDE_PIECE} n'ont pas de {@code assignedTo} : l'assignation (mission
 * section 11) n'existe aujourd'hui que sur {@code Finding} ; etendre {@code DemandePiece} de la
 * meme facon serait une extension P2 directe si le besoin se confirme.
 */
public record InboxItemDto(
        UUID id,
        String itemType,
        UUID entrepriseId,
        String entrepriseNom,
        String severite,
        String titre,
        LocalDate dueDate,
        UUID assignedToId,
        String assignedToNom,
        OffsetDateTime createdAt
) {
}
