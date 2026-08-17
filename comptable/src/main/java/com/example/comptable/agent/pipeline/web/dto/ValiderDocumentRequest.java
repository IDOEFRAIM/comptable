package com.example.comptable.agent.pipeline.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Corrections optionnelles apportees par le comptable avant de valider une piece dans l'inbox.
 * Tous les champs sont nullables : un champ absent signifie "l'extraction VLM etait correcte,
 * ne pas y toucher", pour permettre une validation en un clic quand rien n'a besoin d'etre corrige
 * (validation par lot).
 */
public record ValiderDocumentRequest(
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        LocalDate datePiece
) {
}
