package com.example.comptable.admin;

import com.example.comptable.admin.AdminDtos.Apercu;
import com.example.comptable.admin.AdminDtos.EvenementAudit;
import com.example.comptable.admin.AdminDtos.Page;
import com.example.comptable.security.UtilisateurPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Vues de supervision de la plateforme : aperçu chiffré et journal d'audit global (lecture seule). */
@Service
@RequiredArgsConstructor
public class AdminSupervisionService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public Apercu apercu(UtilisateurPrincipal admin) {
        AdminUtilisateurService.exigerAdmin(admin);
        return jdbc.queryForObject("""
                select (select count(*) from tenant),
                       count(*),
                       count(*) filter (where statut = 'ACTIF'),
                       count(*) filter (where statut <> 'ACTIF'),
                       count(*) filter (where locked_until is not null and locked_until > now()),
                       count(*) filter (where statut = 'ACTIF' and last_login_at is null),
                       (select count(*) from utilisateur_role ur join role r on r.id = ur.role_id where r.code = 'SUPER_ADMIN')
                from utilisateur
                """, (rs, i) -> new Apercu(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)));
    }

    /** Journal d'audit de tous les cabinets, du plus récent au plus ancien. Filtres facultatifs : cabinet, texte contenu dans l'action, période (jours inclus). */
    @Transactional(readOnly = true)
    public Page<EvenementAudit> audit(UtilisateurPrincipal admin, UUID cabinetId, String action, LocalDate depuis, LocalDate jusqu, int page, int taille) {
        AdminUtilisateurService.exigerAdmin(admin);
        int p = Math.max(0, page);
        int n = Math.min(100, Math.max(1, taille));
        List<Object> params = new ArrayList<>();
        String where = " where 1 = 1";
        if (cabinetId != null) {
            where += " and a.tenant_id = ?";
            params.add(cabinetId);
        }
        if (action != null && !action.isBlank()) {
            where += " and a.action ilike ? escape '\\'";
            params.add("%" + action.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (depuis != null) {
            where += " and a.created_at >= ?";
            params.add(depuis.atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (jusqu != null) {
            where += " and a.created_at < ?";
            params.add(jusqu.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        Long total = jdbc.queryForObject("select count(*) from audit_log a" + where, Long.class, params.toArray());
        List<Object> avecPage = new ArrayList<>(params);
        avecPage.add(n);
        avecPage.add((long) p * n);
        List<EvenementAudit> elements = jdbc.query("""
                select a.id, a.created_at, a.action, a.entite, a.entite_id, a.details::text details, a.tenant_id, t.raison_sociale, a.utilisateur_id, u.email
                from audit_log a left join tenant t on t.id = a.tenant_id left join utilisateur u on u.id = a.utilisateur_id
                """ + where + " order by a.created_at desc, a.id desc limit ? offset ?", (rs, i) -> new EvenementAudit(rs.getLong("id"),
                rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC), rs.getString("action"), rs.getString("entite"), rs.getString("entite_id"), details(rs.getString("details")),
                rs.getObject("tenant_id", UUID.class), rs.getString("raison_sociale"), rs.getObject("utilisateur_id", UUID.class), rs.getString("email")), avecPage.toArray());
        return new Page<>(elements, total == null ? 0 : total, p, n);
    }

    private Map<String, Object> details(String brut) {
        if (brut == null || brut.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(brut, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of("brut", brut);
        }
    }
}
