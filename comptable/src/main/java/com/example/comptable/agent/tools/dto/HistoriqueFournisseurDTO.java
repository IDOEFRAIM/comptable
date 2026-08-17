package com.example.comptable.agent.tools.dto;

import java.math.BigDecimal;
import java.util.List;

/** Reponse de l'outil {@code consulter_historique_fournisseur} : statistiques agregees plutot
 * que les pieces completes, suffisant pour que l'agent juge si un montant ou une recurrence est
 * inhabituel par rapport aux habitudes du client avec ce fournisseur. */
public record HistoriqueFournisseurDTO(
        long nombrePieces,
        BigDecimal montantMoyen,
        BigDecimal montantMin,
        BigDecimal montantMax,
        List<PieceHistoriqueDTO> dernieresPieces
) {
}
