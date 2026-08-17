package com.example.comptable.finance.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Mission P1 (section 23-24) : prevision de tresorerie a un horizon donne, avec la distinction
 * obligatoire KNOWN / EXPECTED - jamais presentee comme une certitude. Aucun palier PREDICTED
 * (IA/statistique) n'est fourni dans cette version : avec l'historique de paiement disponible
 * (aucune date de paiement persistee sur {@code Facture} aujourd'hui), une "prediction IA" serait
 * un chiffre fabrique sans valeur reelle - voir docs/P1_ARCHITECTURE.md, limite documentee.
 */
public record PrevisionTresorerieDto(
        LocalDate dateReference,
        int horizonJours,
        BigDecimal soldeActuel,
        BigDecimal encaissementsConnus,
        BigDecimal encaissementsAttendus,
        BigDecimal decaissementsConnus,
        BigDecimal soldePrevisionnel
) {
}
