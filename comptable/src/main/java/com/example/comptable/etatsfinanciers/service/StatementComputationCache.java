package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.comptabilite.balance.AccountingBalanceEngine;
import com.example.comptable.comptabilite.balance.DatasetVersion;
import com.example.comptable.comptabilite.balance.ExerciseBalances;
import com.example.comptable.comptabilite.balance.ReportingView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Memoisation LIMITEE A LA TRANSACTION COURANTE des calculs des etats financiers (soldes des exercices, empreinte des donnees, etats calcules, flux de tresorerie). La synthese du
 * dossier appelle le Compte de resultat, le Bilan, le TFT et les Notes dans une seule transaction : sans cela, les memes soldes sont rechargees et les memes etats recalcules
 * plusieurs fois (le Compte de resultat 4 fois, les ecritures de tresorerie 2 fois).
 *
 * <p>Ce n'est PAS un cache applicatif : rien ne survit a la transaction (nettoyage en fin de transaction), aucune donnee n'est partagee entre utilisateurs, aucune invalidation n'est
 * necessaire. Hors transaction active, le calcul est execute sans memoisation. Reserve aux services d'etats financiers, qui ne modifient jamais la comptabilite.</p>
 */
@Component
@RequiredArgsConstructor
public class StatementComputationCache {

    private static final Object RESSOURCE = new Object();

    private final AccountingBalanceEngine balanceEngine;

    @SuppressWarnings("unchecked")
    public <T> T enCache(List<Object> cle, Supplier<T> calcul) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return calcul.get();
        }
        Map<Object, Object> memo = (Map<Object, Object>) TransactionSynchronizationManager.getResource(RESSOURCE);
        if (memo == null) {
            memo = new HashMap<>();
            TransactionSynchronizationManager.bindResource(RESSOURCE, memo);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(RESSOURCE);
                }
            });
        }
        if (memo.containsKey(cle)) {
            return (T) memo.get(cle);
        }
        T valeur = calcul.get();
        memo.put(cle, valeur);
        return valeur;
    }

    public ExerciseBalances soldes(UUID entrepriseId, UUID exerciceId, ReportingView vue) {
        return enCache(List.of("SOLDES", entrepriseId, exerciceId, vue), () -> balanceEngine.avecComparatif(entrepriseId, exerciceId, vue));
    }

    public DatasetVersion empreinte(UUID entrepriseId, UUID exerciceId, LocalDate debut, LocalDate fin, ReportingView vue) {
        return enCache(List.of("EMPREINTE", entrepriseId, exerciceId, vue), () -> balanceEngine.datasetVersion(entrepriseId, exerciceId, debut, fin, vue));
    }
}
