package com.example.comptable.agent.client;

import com.example.comptable.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Suivi de l'usage de l'IA par cabinet et plafond mensuel d'appels.
 * <p>
 * Jamais critique : une panne du suivi ne doit pas empêcher un appel IA. Chaque écriture a sa propre transaction (les appelants sont souvent en lecture seule) et absorbe ses erreurs.
 * Le cabinet vient du contexte de la requête ; hors requête (tâche de fond), l'appel est compté sans cabinet et aucun plafond ne s'applique.
 */
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

    private final JdbcTemplate jdbc;

    /** Vrai si le cabinet courant a un plafond mensuel et l'a déjà utilisé en entier. Les appels refusés ne comptent pas dans l'usage. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean plafondAtteint() {
        UUID tenant = TenantContext.get();
        if (tenant == null) {
            return false;
        }
        try {
            Boolean atteint = jdbc.queryForObject("""
                    select t.quota_appels_ia_mois is not null
                       and (select count(*) from ai_call_log l
                             where l.tenant_id = t.id and l.statut <> 'REFUSE_PLAFOND' and l.created_at >= date_trunc('month', now())) >= t.quota_appels_ia_mois
                      from tenant t where t.id = ?
                    """, Boolean.class, tenant);
            return Boolean.TRUE.equals(atteint);
        } catch (RuntimeException e) {
            log.warn("Suivi IA : plafond non vérifié ({}), l'appel est autorisé", e.getMessage());
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enregistrer(String operation, String statut, long latenceMs, String erreur) {
        try {
            jdbc.update("insert into ai_call_log (tenant_id, operation, statut, latence_ms, erreur) values (?, ?, ?, ?, ?)", TenantContext.get(), operation, statut,
                    (int) Math.min(latenceMs, Integer.MAX_VALUE), erreur == null ? null : erreur.substring(0, Math.min(200, erreur.length())));
        } catch (RuntimeException e) {
            log.warn("Suivi IA : appel non enregistré ({})", e.getMessage());
        }
    }
}
