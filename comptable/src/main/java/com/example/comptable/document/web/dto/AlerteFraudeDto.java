package com.example.comptable.document.web.dto;

import com.example.comptable.document.domain.Document;

import java.util.List;
import java.util.Map;

/**
 * Projection legere du verdict d'{@code agent.fraude.AnalyseFraudeService} (Sprint 7) pour les
 * DTO exposes au frontend ({@link DocumentDto},
 * {@code agent.pipeline.web.dto.DocumentEnAttenteValidationDto}). Lit directement la Map JSON
 * brute persistee sur {@link Document#getRapportFraude()} plutot que de deserialiser un
 * {@code RapportFraude} type via {@code ObjectMapper} : ces DTO sont construits par des methodes
 * statiques sans injection de dependance, et le module {@code document} n'a pas a dependre du
 * module {@code agent.fraude} pour projeter trois champs.
 */
public record AlerteFraudeDto(String code, String motif, String gravite, String actionRecommandee) {

    public static String scoreRisque(Document document) {
        if (document.getRapportFraude() == null) {
            return null;
        }
        Object score = document.getRapportFraude().get("scoreRisque");
        return score != null ? score.toString() : null;
    }

    /** {@code actionRecommandee} est absent des rapports persistes avant le Sprint 8 : lu comme
     * {@code null} plutot que la chaine "null" pour ces anciennes pieces, cf. {@link #texte}. */
    @SuppressWarnings("unchecked")
    public static List<AlerteFraudeDto> alertes(Document document) {
        if (document.getRapportFraude() == null) {
            return List.of();
        }
        Object brut = document.getRapportFraude().get("alertes");
        if (!(brut instanceof List<?> liste)) {
            return List.of();
        }
        return liste.stream()
                .filter(Map.class::isInstance)
                .map(o -> (Map<String, Object>) o)
                .map(m -> new AlerteFraudeDto(
                        texte(m, "code"),
                        texte(m, "motif"),
                        texte(m, "gravite"),
                        texte(m, "actionRecommandee")))
                .toList();
    }

    private static String texte(Map<String, Object> alerte, String cle) {
        Object valeur = alerte.get(cle);
        return valeur != null ? valeur.toString() : null;
    }
}
