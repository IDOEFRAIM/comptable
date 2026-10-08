package com.example.comptable.fiscalite.vat;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Déclencheur proactif de la TVA : une écriture validée créée ou modifiée signale ici (entreprise, date). Les signaux d'une transaction sont COALESCÉS ; après le commit, les périodes TVA non finalisées qui
 * couvrent ces dates sont recalculées (constats publiés ou résolus) sans qu'aucun écran ne soit ouvert. Une entreprise sans période TVA ouverte ne coûte qu'une requête.
 */
@Component
public class VatChangeTracker {

    private final VatWorkspaceService service;

    public VatChangeTracker(@Lazy VatWorkspaceService service) {
        this.service = service;
    }

    public void marquer(UUID entrepriseId, LocalDate date) {
        if (entrepriseId == null || date == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            service.recalculerPeriodesCouvrant(entrepriseId, Set.of(date));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<UUID, Set<LocalDate>> signaux = (Map<UUID, Set<LocalDate>>) TransactionSynchronizationManager.getResource(this);
        if (signaux == null) {
            Map<UUID, Set<LocalDate>> nouveaux = new LinkedHashMap<>();
            TransactionSynchronizationManager.bindResource(this, nouveaux);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(VatChangeTracker.this);
                }

                @Override
                public void afterCommit() {
                    nouveaux.forEach(service::recalculerPeriodesCouvrant);
                }
            });
            signaux = nouveaux;
        }
        signaux.computeIfAbsent(entrepriseId, k -> new HashSet<>()).add(date);
    }
}
