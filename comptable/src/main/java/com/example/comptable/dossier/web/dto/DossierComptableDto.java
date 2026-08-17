package com.example.comptable.dossier.web.dto;

import com.example.comptable.agent.finding.web.dto.FindingDto;

import java.util.List;

/**
 * Vue calculee du dossier comptable d'une entreprise (mission P0, section 8) : jamais persistee
 * (voir DossierComptableService), toujours recalculee a la lecture a partir de donnees deja
 * existantes (comptes, findings, factures, documents...). Le score est deterministe, jamais
 * calcule par un modele de langage.
 */
public record DossierComptableDto(
        String entrepriseNom,
        int scoreGlobal,
        String statut,
        List<ScoreDomaineDto> scoresParDomaine,
        List<FindingDto> actionsPrioritaires,
        List<FindingDto> blockingItems
) {
}
