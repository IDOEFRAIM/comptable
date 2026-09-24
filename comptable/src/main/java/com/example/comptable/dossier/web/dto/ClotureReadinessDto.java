package com.example.comptable.dossier.web.dto;

import java.util.List;

/** "Closing Readiness" (mission P0, section 19) : READY / READY_WITH_REVIEW / NOT_READY. Purement
 * consultatif - ne declenche jamais {@code ClotureExerciceService.cloturer}, qui reste le seul
 * point d'entree pour une cloture reelle, toujours a l'initiative explicite d'un comptable. */
public record ClotureReadinessDto(
        String statut,
        List<String> blockingItems,
        List<String> warnings,
        List<String> recommendations,
        List<ControlItem> checklist
) {
}
