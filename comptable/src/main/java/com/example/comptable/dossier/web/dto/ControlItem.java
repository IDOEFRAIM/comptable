package com.example.comptable.dossier.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Une ligne de la checklist de cloture (mission cloture Etape 4, section 14). {@code statut} vaut
 * OK/WARNING/BLOCKING/NOT_APPLICABLE - NOT_APPLICABLE signale honnetement qu'aucune logique
 * metier suffisamment approfondie n'a ete construite pour ce point dans ce perimetre (jamais
 * simule comme OK). {@code evidence} est un texte libre bref (ex: liste de titres de findings,
 * nombre d'ecritures en brouillon) permettant a l'utilisateur de comprendre le statut sans aller
 * chercher ailleurs.
 *
 * <p>Phase 1 (lot 1.11, explicabilite) : les champs additifs {@code entityType}, {@code entityId},
 * {@code explanation}, {@code relatedEntityIds} et {@code metadata} permettent a l'interface de
 * detailler un point (compteurs, montants) et de naviguer jusqu'aux objets responsables. Ils sont
 * facultatifs : l'ancien constructeur a six arguments reste valable et le JSON existant n'est
 * enrichi que de champs supplementaires.</p>
 */
public record ControlItem(
        String code,
        String libelle,
        String statut,
        String severite,
        String message,
        String evidence,
        String entityType,
        String entityId,
        String explanation,
        List<String> relatedEntityIds,
        Map<String, Object> metadata,
        String domaine
) {

    public ControlItem {
        relatedEntityIds = relatedEntityIds != null ? List.copyOf(relatedEntityIds) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /** Compatibilite : point sans domaine (renseigne ensuite par la pre-cloture). */
    public ControlItem(String code, String libelle, String statut, String severite, String message, String evidence, String entityType, String entityId,
                       String explanation, List<String> relatedEntityIds, Map<String, Object> metadata) {
        this(code, libelle, statut, severite, message, evidence, entityType, entityId, explanation, relatedEntityIds, metadata, null);
    }

    /** Meme point, range dans un domaine de revision. */
    public ControlItem avecDomaine(String domaine) {
        return new ControlItem(code, libelle, statut, severite, message, evidence, entityType, entityId, explanation, relatedEntityIds, metadata, domaine);
    }

    /** Compatibilite : point simple sans detail structure (comportement historique). */
    public ControlItem(String code, String libelle, String statut, String severite, String message, String evidence) {
        this(code, libelle, statut, severite, message, evidence, null, null, null, List.of(), Map.of(), null);
    }

    /** Point detaille : message court + explication chiffree + objets responsables. */
    public static ControlItem detaille(String code, String libelle, String statut, String severite, String message,
                                       String evidence, String entityType, String entityId, String explanation,
                                       List<String> relatedEntityIds, Map<String, Object> metadata) {
        return new ControlItem(code, libelle, statut, severite, message, evidence, entityType, entityId, explanation,
                relatedEntityIds, metadata, null);
    }
}
