package com.example.comptable.finance.web.dto;

/** Mission P1 (section 20) : comparaison periode actuelle / periode de reference
 * ({@code PERIODE_PRECEDENTE} ou {@code AN_PRECEDENT}). */
public record ComparaisonFinancialSnapshotDto(
        FinancialSnapshotDto periodeActuelle,
        FinancialSnapshotDto periodeComparaison,
        String typeComparaison
) {
}
