package com.example.comptable.fiscalite.vat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Jeton de concurrence agrégé d'une période fiscale ({@code tax_period.version}). INVARIANT : toute mutation fonctionnelle d'une période (classification, validation en masse, ajustement, crédit d'ouverture,
 * transition de statut, paiement, annulation de paiement, recalcul qui change les données) incrémente la version <b>exactement une fois par transaction</b>, quel que soit le nombre d'écritures internes
 * qu'elle déclenche. Un client qui a lu la version N et écrit avec {@code expectedVersion = N} après qu'un autre a écrit reçoit 409. Le marqueur est rattaché aux synchronisations de la transaction
 * courante : une transaction imbriquée (REQUIRES_NEW) a son propre marqueur.
 */
final class PeriodVersion {

    /** Périodes déjà incrémentées dans la transaction à laquelle ce marqueur est rattaché. */
    private static final class Marqueur implements TransactionSynchronization {
        final Set<UUID> periodes = new HashSet<>();
    }

    private PeriodVersion() {
    }

    /** Incrémente la version de la période, une seule fois dans la transaction courante. */
    static void bumpOnce(JdbcTemplate jdbc, UUID periodeId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            Marqueur marqueur = TransactionSynchronizationManager.getSynchronizations().stream().filter(Marqueur.class::isInstance).map(Marqueur.class::cast).findFirst().orElse(null);
            if (marqueur == null) {
                marqueur = new Marqueur();
                TransactionSynchronizationManager.registerSynchronization(marqueur);
            }
            if (!marqueur.periodes.add(periodeId)) {
                return;
            }
        }
        jdbc.update("update tax_period set version = version + 1, updated_at = now() where id = ?", periodeId);
    }
}
