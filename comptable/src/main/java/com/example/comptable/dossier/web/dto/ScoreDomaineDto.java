package com.example.comptable.dossier.web.dto;

/**
 * Un domaine du dossier : {@code score} (100 moins des pénalités fixes, indicatif) et {@code elementsOuverts} (nombre réel d'éléments à traiter, ce qui est affiché à l'utilisateur : un compte d'objets
 * est vérifiable, un pourcentage à barème fixe ne l'est pas).
 */
public record ScoreDomaineDto(String domaine, int score, int elementsOuverts) {

    public ScoreDomaineDto(String domaine, int score) {
        this(domaine, score, 0);
    }
}
