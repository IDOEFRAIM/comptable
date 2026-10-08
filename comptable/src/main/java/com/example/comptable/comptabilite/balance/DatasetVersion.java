package com.example.comptable.comptabilite.balance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Empreinte technique des donnees comptables qui alimentent un calcul : permettra a un futur snapshot d'etat financier de PROUVER de quelles donnees il
 * provient. Ce n'est PAS une version reglementaire (SYSCOHADA) : une version technique, derivee de la base (aucune ecriture supplementaire a chaque
 * mutation, donc aucun point de contention), voir {@code docs/accounting-balance-engine.md}.
 *
 * @param fingerprint SHA-256 de (entreprise, vue, bornes, nombre de lignes, totaux, derniere creation) : deux calculs sur des donnees identiques ont la meme
 *                    empreinte, toute ecriture ajoutee/validee/contrepassee dans le perimetre la change.
 */
public record DatasetVersion(UUID entrepriseId, UUID exerciceId, ReportingView view, java.time.LocalDate from, java.time.LocalDate to,
                             OffsetDateTime generatedAt, long lineCount, long entryCount, BigDecimal totalDebit, BigDecimal totalCredit,
                             OffsetDateTime lastEntryCreatedAt, String fingerprint) {
}
