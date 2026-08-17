package com.example.comptable.agent.tools.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Resume d'une piece deja enregistree, utilise par les deux outils de lecture de l'agent
 * comptable autonome (doublon et historique fournisseur) - jamais le document complet, l'agent
 * n'a besoin que de la date et du montant pour juger d'une recurrence ou d'un doublon. */
public record PieceHistoriqueDTO(LocalDate datePiece, BigDecimal montantTtc) {
}
