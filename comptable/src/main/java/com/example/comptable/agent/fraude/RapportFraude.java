package com.example.comptable.agent.fraude;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Sortie structuree d'{@link AnalyseFraudeService#analyser}, persistee telle quelle (en JSON,
 * cf. {@code Document.rapportFraude}) pour servir a la fois de trace d'audit et de source unique
 * pour le badge de l'inbox de validation et pour le routage du pipeline agentique
 * ({@code agent.pipeline.AnalyseFraudeNode}) - jamais recalculee deux fois pour un meme document.
 */
public record RapportFraude(NiveauRisqueFraude scoreRisque, List<AlerteFraude> alertes, OffsetDateTime analyseAt) {

    /** Agrege une liste d'alertes en rapport : le score global est celui de l'alerte la plus
     * grave (une seule alerte CRITIQUE suffit a rendre tout le rapport CRITIQUE), jamais une
     * moyenne - la moindre alerte forte doit rester visible telle quelle, pas diluee. */
    public static RapportFraude depuisAlertes(List<AlerteFraude> alertes) {
        NiveauRisqueFraude score = alertes.stream()
                .map(AlerteFraude::gravite)
                .max(Comparator.comparingInt(NiveauRisqueFraude::ordinal))
                .orElse(NiveauRisqueFraude.FAIBLE);
        return new RapportFraude(score, List.copyOf(alertes), OffsetDateTime.now());
    }

    public boolean estCritique() {
        return scoreRisque == NiveauRisqueFraude.CRITIQUE;
    }
}
