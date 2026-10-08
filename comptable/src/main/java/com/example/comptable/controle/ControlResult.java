package com.example.comptable.controle;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contrat COMMUN de sortie des controles (Phase 1, lot 1.6) : un constat technique, explicable et
 * navigable, quelle que soit la source ({@code ControlEngine}, {@code AnalyseFraudeService},
 * {@code ClotureReadinessService}, controles bloquants du moteur comptable...). Ce n'est PAS un
 * nouveau moteur : les moteurs existants continuent de decider ; ce type ne fait qu'uniformiser ce
 * qu'ils exposent.
 *
 * <p>Distinction voulue : un {@code ControlResult} est un CONSTAT ; un {@code Finding} est
 * l'element de travail persiste quand ce constat demande une action humaine
 * ({@link ControlResultPublisher}). Un constat sans suite (ex. point de checklist OK) n'est pas
 * persiste.</p>
 *
 * @param code             identifiant stable et lisible par machine (ex. {@code DOUBLON_POTENTIEL})
 * @param entityType       type de l'objet concerne (ex. {@code DOCUMENT}, {@code EXERCICE}, {@code RELEVE})
 * @param entityId         identifiant de l'objet concerne (texte : certaines sources ne sont pas des UUID)
 * @param message          phrase courte affichable
 * @param explanation      explication detaillee (valeurs chiffrees, cause) pour que le comptable comprenne
 * @param relatedEntityIds autres objets responsables, pour la navigation (ex. lignes de releve non pointees)
 * @param metadata         valeurs structurees (montants, compteurs) sans framework : une simple map
 * @param suggestedAction  le geste suivant recommande, si connu
 */
public record ControlResult(
        String code,
        ControlCategory category,
        ControlSeverity severity,
        ControlStatus status,
        UUID entrepriseId,
        String entityType,
        String entityId,
        String message,
        String explanation,
        boolean blocking,
        List<String> relatedEntityIds,
        Map<String, Object> metadata,
        String suggestedAction,
        OffsetDateTime detectedAt
) {

    public ControlResult {
        relatedEntityIds = relatedEntityIds != null ? List.copyOf(relatedEntityIds) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /** Constat ouvert ; {@code blocking} est derive de la gravite pour eviter toute incoherence. */
    public static ControlResult ouvert(String code, ControlCategory category, ControlSeverity severity,
                                        UUID entrepriseId, String entityType, String entityId, String message,
                                        String explanation, List<String> relatedEntityIds,
                                        Map<String, Object> metadata, String suggestedAction) {
        return new ControlResult(code, category, severity, ControlStatus.OPEN, entrepriseId, entityType, entityId,
                message, explanation, severity == ControlSeverity.BLOCKING, relatedEntityIds, metadata,
                suggestedAction, OffsetDateTime.now());
    }

    /** Cle de deduplication d'un constat : meme controle sur le meme objet = meme constat. */
    public String sourceKey() {
        return entityId + "#" + code;
    }
}
