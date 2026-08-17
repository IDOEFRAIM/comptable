package com.example.comptable.agent.tools.dto;

import java.util.List;

/** Reponse de l'outil {@code verifier_doublon_montant}. */
public record DoublonVerificationResponseDTO(boolean doublonPotentielDetecte, List<PieceHistoriqueDTO> piecesSimilaires) {
}
