package com.example.comptable.etatsfinanciers.sync;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Point de déclenchement unique de la synchronisation proactive. Les opérations métier (écriture comptable créée, validée ou modifiée ; qualification d'un flux de trésorerie ; note modifiée ou revue)
 * signalent ici « les contrôles de ce dossier sont à resynchroniser » ({@link FinancialControlEntityListener}). Les signaux d'une même transaction sont COALESCÉS : un import de 10 000 écritures
 * dans une transaction produit UNE synchronisation, après le commit. Si la transaction est annulée, rien n'est synchronisé.
 */
@Component
public class FinancialControlChangeTracker {

    private record Cle(UUID entrepriseId, UUID exerciceId) {
    }

    private final FinancialControlSynchronizationService service;

    public FinancialControlChangeTracker(@Lazy FinancialControlSynchronizationService service) {
        this.service = service;
    }

    public void marquer(UUID entrepriseId, UUID exerciceId, Set<FinancialControlDomain> domaines) {
        if (entrepriseId == null || exerciceId == null || FinancialControlSynchronizationService.enCours()) {
            return;                                                                                 // écritures de la synchronisation elle-même
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            service.synchroniserSansEchec(entrepriseId, exerciceId, domaines);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<Cle, Set<FinancialControlDomain>> signaux = (Map<Cle, Set<FinancialControlDomain>>) TransactionSynchronizationManager.getResource(this);
        if (signaux == null) {
            Map<Cle, Set<FinancialControlDomain>> nouveaux = new LinkedHashMap<>();
            TransactionSynchronizationManager.bindResource(this, nouveaux);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(FinancialControlChangeTracker.this);
                }

                @Override
                public void afterCommit() {
                    nouveaux.forEach((cle, d) -> service.synchroniserSansEchec(cle.entrepriseId(), cle.exerciceId(), d));
                }
            });
            signaux = nouveaux;
        }
        signaux.computeIfAbsent(new Cle(entrepriseId, exerciceId), k -> EnumSet.noneOf(FinancialControlDomain.class)).addAll(domaines);
    }
}
