package com.example.comptable.cabinet.web.dto;

/**
 * Mission P1 (section 36) : KPIs de production du cabinet, actionnables plutot que decoratifs -
 * uniquement ceux calculables a partir de donnees reelles deja modelisees. Le taux de
 * rapprochement, le taux de validation IA et le temps moyen avant cloture ne sont pas fournis
 * dans cette version : les calculer correctement demanderait de nouvelles requetes batchees
 * cross-entreprises non encore construites - mieux vaut livrer un sous-ensemble solide que des
 * chiffres approximatifs (mission section 68). Documente comme limite P2.
 */
public record CabinetAnalyticsDto(
        int nombreDossiers,
        int dossiersAJour,
        int dossiersEnRetard,
        Double tempsMoyenResolutionJours,
        Double tauxDemandesResoluesPourcent
) {
}
