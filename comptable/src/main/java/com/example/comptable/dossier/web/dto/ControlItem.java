package com.example.comptable.dossier.web.dto;

/**
 * Une ligne de la checklist de cloture (mission cloture Etape 4, section 14). {@code statut} vaut
 * OK/WARNING/BLOCKING/NOT_APPLICABLE - NOT_APPLICABLE signale honnetement qu'aucune logique
 * metier suffisamment approfondie n'a ete construite pour ce point dans ce perimetre (jamais
 * simule comme OK). {@code evidence} est un texte libre bref (ex: liste de titres de findings,
 * nombre d'ecritures en brouillon) permettant a l'utilisateur de comprendre le statut sans aller
 * chercher ailleurs.
 */
public record ControlItem(
        String code,
        String libelle,
        String statut,
        String severite,
        String message,
        String evidence
) {
}
