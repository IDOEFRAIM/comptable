package com.example.comptable.identite.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Limites de l'abonnement d'un cabinet, fixées par l'administrateur de la plateforme ({@code tenant.quota_*}, null = illimité). Appliquées à l'ajout d'un membre et à la création d'un dossier : un refus
 * explique la limite et à qui s'adresser, il ne coupe jamais l'accès à l'existant.
 */
@Service
@RequiredArgsConstructor
public class QuotaCabinetService {

    private final JdbcTemplate jdbc;

    /** Membres de cabinet actifs (experts et collaborateurs) : les clients du portail ne comptent pas. */
    public static final String MEMBRES_ACTIFS = """
            select count(*) from utilisateur u where u.tenant_id = ? and u.statut = 'ACTIF' and exists (
                select 1 from utilisateur_role ur join role r on r.id = ur.role_id where ur.utilisateur_id = u.id and r.code in ('EXPERT_COMPTABLE', 'COLLABORATEUR'))""";

    @Transactional(readOnly = true)
    public void verifierAjoutMembre(UUID tenantId) {
        verifier(tenantId, "quota_utilisateurs", MEMBRES_ACTIFS, "membre(s) actif(s) dans l'équipe");
    }

    @Transactional(readOnly = true)
    public void verifierCreationDossier(UUID tenantId) {
        verifier(tenantId, "quota_dossiers", "select count(*) from entreprise e where e.tenant_id = ? and e.statut = 'ACTIF'", "dossier(s) actif(s)");
    }

    private void verifier(UUID tenantId, String colonne, String comptage, String unite) {
        if (tenantId == null) {
            return;
        }
        List<Integer> limites = jdbc.query("select " + colonne + " from tenant where id = ?", (rs, i) -> rs.getObject(1, Integer.class), tenantId);
        Integer limite = limites.isEmpty() ? null : limites.get(0);
        if (limite == null) {
            return;
        }
        Long actuel = jdbc.queryForObject(comptage, Long.class, tenantId);
        if (actuel != null && actuel >= limite) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'abonnement de votre cabinet est limité à " + limite + " " + unite + " (vous en avez " + actuel + "). Contactez l'équipe Warimara pour augmenter cette limite.");
        }
    }
}
