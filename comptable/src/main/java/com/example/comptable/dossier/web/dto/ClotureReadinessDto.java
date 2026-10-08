package com.example.comptable.dossier.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * "Closing Readiness" : READY / READY_WITH_WARNINGS / NOT_READY. Aucun pourcentage : {@link Resume} compte ce qui bloque, ce qui avertit et ce qui est
 * en ordre. {@code evaluatedAt} date l'evaluation, {@code controlesExecutesLe} la derniere execution des controles dont elle s'appuie : une cloture
 * relance toujours les controles avant de decider. Purement consultatif - ne declenche jamais {@code ClotureExerciceService.cloturer}.
 */
public record ClotureReadinessDto(
        String statut,
        List<String> blockingItems,
        List<String> warnings,
        List<String> recommendations,
        List<ControlItem> checklist,
        Resume resume,
        OffsetDateTime evaluatedAt,
        OffsetDateTime controlesExecutesLe
) {

    /** « 3 bloquants, 8 avertissements, 12 en ordre » : des comptes, jamais un score. */
    public record Resume(long bloquants, long avertissements, long enOrdre, long nonApplicables) {
    }

    /** Compatibilite : evaluation sans resume ni horodatage. */
    public ClotureReadinessDto(String statut, List<String> blockingItems, List<String> warnings, List<String> recommendations, List<ControlItem> checklist) {
        this(statut, blockingItems, warnings, recommendations, checklist, null, null, null);
    }
}
