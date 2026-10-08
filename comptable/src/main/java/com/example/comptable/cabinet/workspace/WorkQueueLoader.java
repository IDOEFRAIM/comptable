package com.example.comptable.cabinet.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * File « À traiter » : les constats ({@code Finding}, source de vérité des anomalies) que le cabinet peut traiter lui-même, et les pièces reçues à rattacher. Aucune seconde table d'alertes.
 * Paginée côté SQL (tri : bloquants, importants, puis le reste ; le plus ancien d'abord) : le volume affiché reste borné quel que soit le nombre de constats. Les constats dont la pièce a
 * déjà été demandée au client n'y figurent pas : ils relèvent de « En attente client ».
 */
@Component
public class WorkQueueLoader {

    private static final String BLOQUANT = "(f.severity = 'CRITICAL' or f.control_severity = 'BLOCKING')";

    private final JdbcTemplate jdbc;

    public WorkQueueLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record WorkRow(String kind, UUID id, UUID entrepriseId, String entrepriseNom, UUID exerciceId, String titre, String categorie, boolean bloquant, String severite,
                          OffsetDateTime depuis, UUID responsableId, String responsableNom, String sourceType, String controlCode) {
    }

    public record Resultat(List<WorkRow> lignes, long total) {
    }

    /** @param mesUtilisateurId si non nul, ne garde que les éléments dont l'utilisateur est le responsable (constat assigné, sinon collaborateur du dossier) ou l'expert du dossier. */
    @Transactional(readOnly = true)
    public Resultat charger(UUID tenantId, UUID mesUtilisateurId, int page, int taille) {
        String base = """
                with courant as (
                    select distinct on (x.entreprise_id) x.entreprise_id, x.id exercice_id
                    from exercice_comptable x join entreprise e on e.id = x.entreprise_id
                    where e.tenant_id = ? order by x.entreprise_id, (x.statut = 'OUVERT') desc, (case when x.statut = 'OUVERT' then x.date_fin end) asc nulls last, x.date_fin desc),
                items as (
                    select 'FINDING' kind, f.id, f.entreprise_id, f.title titre, f.category::text categorie, f.severity::text severite, %s bloquant, f.created_at depuis,
                           f.assigned_to_id responsable_id, f.source_type, f.control_code,
                           case when %s then 0 when f.severity = 'IMPORTANT' then 1 else 2 end rang
                    from finding f join entreprise e on e.id = f.entreprise_id
                    where e.tenant_id = ? and e.statut = 'ACTIF' and f.status in ('OPEN', 'IN_PROGRESS')
                      and not exists (select 1 from demande_piece d where d.finding_id = f.id and d.statut in ('EN_ATTENTE', 'A_ENVOYER', 'ECHEC_ENVOI'))
                    union all
                    select 'DEMANDE_PIECE', d.id, d.entreprise_id, d.libelle, 'DOCUMENT', 'REVIEW', false, d.created_at, null::uuid, 'DEMANDE_PIECE', 'DEMANDE_PIECE', 2
                    from demande_piece d join entreprise e on e.id = d.entreprise_id
                    where e.tenant_id = ? and e.statut = 'ACTIF' and d.statut = 'RECU'
                    union all
                    select 'TAX_REVIEW', p.id, p.entreprise_id, 'Déclaration TVA prête pour revue (' || p.period_start || ' → ' || p.period_end || ')', 'TAX', 'REVIEW', false, p.updated_at, null::uuid,
                           'TAX_PERIOD', 'TAX_REVIEW', 1
                    from tax_period p join entreprise e on e.id = p.entreprise_id
                    where e.tenant_id = ? and e.statut = 'ACTIF' and p.status = 'READY_FOR_REVIEW'
                    union all
                    select 'TAX_PAYMENT', p.id, p.entreprise_id, 'TVA à payer (' || p.period_start || ' → ' || p.period_end || ') : reste ' || (s.net - coalesce(pa.paye, 0))::text, 'TAX', 'REVIEW', false, p.updated_at, null::uuid,
                           'TAX_PERIOD', 'TAX_PAYMENT', 2
                    from tax_period p join entreprise e on e.id = p.entreprise_id
                    join lateral (select (t.payload::jsonb -> 'totals' ->> 'netDeclared')::numeric net from tax_declaration_snapshot t where t.tax_period_id = p.id) s on true
                    left join lateral (select sum(tp.amount) paye from tax_payment tp where tp.tax_period_id = p.id and tp.status = 'ACTIVE') pa on true
                    where e.tenant_id = ? and e.statut = 'ACTIF' and p.status = 'FINAL' and s.net > coalesce(pa.paye, 0))
                select i.*, e.raison_sociale, c.exercice_id, coalesce(i.responsable_id, a.collaborateur_id) resp_id,
                       trim(coalesce(u.nom, '') || ' ' || coalesce(u.prenom, '')) resp_nom
                from items i
                join entreprise e on e.id = i.entreprise_id
                join courant c on c.entreprise_id = i.entreprise_id
                left join dossier_affectation a on a.entreprise_id = c.entreprise_id and a.exercice_id = c.exercice_id
                left join utilisateur u on u.id = coalesce(i.responsable_id, a.collaborateur_id)
                """.formatted(BLOQUANT, BLOQUANT);
        List<Object> params = new ArrayList<>(List.of(tenantId, tenantId, tenantId, tenantId, tenantId));
        String filtre = "";
        if (mesUtilisateurId != null) {
            filtre = " where (coalesce(i.responsable_id, a.collaborateur_id) = ? or a.expert_id = ?)";
            params.add(mesUtilisateurId);
            params.add(mesUtilisateurId);
        }
        Long total = jdbc.queryForObject("select count(*) from (" + base + filtre + ") q", Long.class, params.toArray());
        List<Object> paramsPage = new ArrayList<>(params);
        paramsPage.add(taille);
        paramsPage.add((long) page * taille);
        List<WorkRow> lignes = jdbc.query(base + filtre + " order by i.rang, i.depuis asc limit ? offset ?", (rs, n) -> {
            String nom = rs.getString("resp_nom");
            java.sql.Timestamp ts = rs.getTimestamp("depuis");
            return new WorkRow(rs.getString("kind"), rs.getObject("id", UUID.class), rs.getObject("entreprise_id", UUID.class), rs.getString("raison_sociale"),
                    rs.getObject("exercice_id", UUID.class), rs.getString("titre"), rs.getString("categorie"), rs.getBoolean("bloquant"), rs.getString("severite"),
                    ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC), rs.getObject("resp_id", UUID.class), nom == null || nom.isBlank() ? null : nom,
                    rs.getString("source_type"), rs.getString("control_code"));
        }, paramsPage.toArray());
        return new Resultat(lignes, total == null ? 0 : total);
    }
}
