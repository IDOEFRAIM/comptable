package com.example.comptable.telemetry.web.dto;

/** Mission P1.9 (section 6/7) : AUTOMATIC = applique sans validation humaine (niveau 3 /
 * decision MATCH_AUTO) ; AI_ASSISTED = une proposition IA existait et un humain l'a
 * validee/rejetee ; HUMAN_ONLY = aucune proposition exploitable n'a jamais abouti (expiree).
 * {@code enAttente} (statut PROPOSED, pas encore resolu) est explicitement exclu du calcul du
 * taux - ni automatise ni manuel tant que non tranche. */
public record HumanTouchRateDto(
        int automatique,
        int assisteParIa,
        int humainSeul,
        int enAttente,
        int totalResolu,
        Double humanTouchRatePourcent,
        Double automationRatePourcent
) {
}
