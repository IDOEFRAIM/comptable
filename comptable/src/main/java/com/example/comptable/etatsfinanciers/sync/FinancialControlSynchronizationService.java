package com.example.comptable.etatsfinanciers.sync;

import com.example.comptable.etatsfinanciers.service.StatementControlPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronisation PROACTIVE des Findings des états financiers : recalcule seulement les contrôles concernés (compte de résultat, bilan, TFT, notes) et publie / résout les constats, sans qu'aucun
 * écran ne soit ouvert. Le Cabinet Workspace lit des Findings déjà synchronisés : il ne recalcule jamais d'état.
 *
 * <p>Idempotente : {@link StatementControlPublisher} déduplique (un constat actif par contrôle et par objet) et résout ce qui a disparu (source SYSTEM) ; deux recalculs identiques ne changent rien.
 * Chaque synchronisation s'exécute dans sa propre transaction ({@code REQUIRES_NEW}), après le commit de l'opération métier qui l'a déclenchée ({@link FinancialControlChangeTracker}) : une anomalie
 * de calcul ne peut jamais faire échouer une saisie comptable.</p>
 */
@Service
public class FinancialControlSynchronizationService {

    private static final Logger LOG = LoggerFactory.getLogger(FinancialControlSynchronizationService.class);
    private static final ThreadLocal<Boolean> EN_COURS = ThreadLocal.withInitial(() -> false);

    private final StatementControlPublisher publisher;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;

    public FinancialControlSynchronizationService(StatementControlPublisher publisher, PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
    }

    /** Vrai pendant une synchronisation : les écritures de la synchronisation elle-même (lignes de notes recalculées) ne doivent pas en redéclencher une. */
    static boolean enCours() {
        return EN_COURS.get();
    }

    /** Synchronise les domaines demandés d'un dossier (entreprise + exercice). */
    public void synchroniser(UUID entrepriseId, UUID exerciceId, Set<FinancialControlDomain> domaines) {
        boolean dejaEnCours = EN_COURS.get();
        EN_COURS.set(true);
        try {
            transaction.executeWithoutResult(statut -> {
                // ordre stable : états comptables d'abord, notes en dernier (elles écrivent leurs propres lignes)
                if (domaines.contains(FinancialControlDomain.COMPTE_RESULTAT)) {
                    publisher.publierCompteResultat(entrepriseId, exerciceId, null);
                }
                if (domaines.contains(FinancialControlDomain.BILAN)) {
                    publisher.publierBilan(entrepriseId, exerciceId, null);
                }
                if (domaines.contains(FinancialControlDomain.TFT)) {
                    publisher.publierTft(entrepriseId, exerciceId, null);
                }
                if (domaines.contains(FinancialControlDomain.NOTES)) {
                    publisher.publierNotes(entrepriseId, exerciceId, null);
                }
            });
        } finally {
            EN_COURS.set(dejaEnCours);
        }
    }

    /** Variante qui n'interrompt jamais l'appelant : une erreur est journalisée (la prochaine opération ou un backfill resynchronisera). */
    public void synchroniserSansEchec(UUID entrepriseId, UUID exerciceId, Set<FinancialControlDomain> domaines) {
        try {
            synchroniser(entrepriseId, exerciceId, domaines);
        } catch (RuntimeException e) {
            LOG.warn("Synchronisation des controles financiers impossible (entreprise {}, exercice {}, {}) : {}", entrepriseId, exerciceId, domaines, e.toString());
        }
    }

    /**
     * Backfill explicite : synchronise tous les exercices OUVERTS des entreprises actives d'un cabinet (dossiers existants avant la synchronisation proactive, ou après une anomalie).
     * Action volontaire (jamais un cron) ; retourne le nombre de dossiers synchronisés.
     */
    public int synchroniserCabinet(UUID tenantId) {
        List<UUID[]> dossiers = jdbc.query("""
                select e.id, x.id from entreprise e join exercice_comptable x on x.entreprise_id = e.id
                where e.tenant_id = ? and e.statut = 'ACTIF' and x.statut = 'OUVERT' order by e.raison_sociale, x.date_fin
                """, (rs, i) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, tenantId);
        int n = 0;
        for (UUID[] d : dossiers) {
            synchroniserSansEchec(d[0], d[1], EnumSet.allOf(FinancialControlDomain.class));
            n++;
        }
        return n;
    }
}
