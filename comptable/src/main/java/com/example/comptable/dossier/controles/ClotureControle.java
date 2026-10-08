package com.example.comptable.dossier.controles;

import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.dossier.web.dto.ControlItem;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Un groupe de controles deterministes de pre-cloture (Phase 1, lots 1.10/1.11), compose par
 * {@code ClotureReadinessService}. Ce n'est pas un moteur concurrent : chaque implementation lit des
 * donnees DEJA presentes (ecritures, releves, documents, immobilisations...) et renvoie des points de
 * checklist au format existant ({@link ControlItem}), enrichis d'une explication chiffree et des
 * objets responsables.
 *
 * <p>Regle : un point n'est {@code OK} que si le systeme peut l'affirmer avec certitude ; faute de
 * donnees il reste {@code NOT_APPLICABLE} - jamais un resultat invente pour faire « 16/16 ».</p>
 */
public interface ClotureControle {

    /** Plafond d'identifiants listes dans {@code relatedEntityIds} : borne la taille de la reponse. */
    int MAX_OBJETS_LIES = 100;

    /** Exercice OUVERT inspecte (les controles ne s'executent pas sur un exercice deja cloture). */
    record Contexte(UUID entrepriseId, ExerciceComptable exercice) {
    }

    List<ControlItem> evaluer(Contexte contexte);

    // ---------------------------------------------------------------- fabriques de points

    static ControlItem ok(String code, String libelle, String message, Map<String, Object> metadata) {
        return ControlItem.detaille(code, libelle, "OK", null, message, null, null, null, message, List.of(), metadata);
    }

    static ControlItem avertissement(String code, String libelle, String message, String explication,
                                      String entityType, String entityId, List<String> lies, Map<String, Object> metadata) {
        return ControlItem.detaille(code, libelle, "WARNING", "IMPORTANT", message, null, entityType, entityId,
                explication, borner(lies), metadata);
    }

    static ControlItem bloquant(String code, String libelle, String message, String explication,
                                 String entityType, String entityId, List<String> lies, Map<String, Object> metadata) {
        return ControlItem.detaille(code, libelle, "BLOCKING", "CRITICAL", message, null, entityType, entityId,
                explication, borner(lies), metadata);
    }

    static ControlItem nonApplicable(String code, String libelle, String raison) {
        return ControlItem.detaille(code, libelle, "NOT_APPLICABLE", null, raison, null, null, null, raison, List.of(), Map.of());
    }

    private static List<String> borner(List<String> lies) {
        return lies.size() > MAX_OBJETS_LIES ? lies.subList(0, MAX_OBJETS_LIES) : lies;
    }
}
