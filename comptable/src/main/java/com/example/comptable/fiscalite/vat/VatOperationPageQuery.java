package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.vat.VatModel.Operation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Liste paginée des opérations de TVA CÔTÉ BASE : filtres, recherche et tri sont du SQL ; seule la page demandée est lue (jamais toute la période puis un découpage mémoire). Aucun filtre ne dépend
 * d'une règle fiscale : côté (sens de la TVA comptabilisée), traitement effectif (décision humaine, sinon sens, sinon TO_REVIEW), constat fiscal actif, dates, recherche libre.
 */
@Component
public class VatOperationPageQuery {

    private static final UUID AUCUN_COMPTE = new UUID(0L, 0L);
    private static final Pattern NOMBRE = Pattern.compile("^-?\\d+([.,]\\d+)?$");

    private final JdbcTemplate jdbc;

    public VatOperationPageQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param side          SALES / PURCHASES (une opération non déterminable apparaît des deux côtés, à revoir) ou null
     * @param treatment     traitement effectif (décision humaine, sinon dérivé du sens, sinon TO_REVIEW) ou null
     * @param aRevoir       true : opérations à revoir (traitement indéterminé ou constat actif) ; false : sans constat ; null : toutes
     * @param findingActive true : seulement celles qui ont un constat fiscal actif
     * @param q             numéro de pièce, document, libellé, tiers, identifiant d'écriture ou montant
     * @param sort          date | document | amount | status | tiers
     * @param ids           restreint aux écritures données (validation en masse, détail d'une opération)
     */
    public record Query(UUID entrepriseId, UUID periodeId, LocalDate debut, LocalDate fin, UUID compteSortie, UUID compteEntree, String side, String treatment, Boolean aRevoir, boolean findingActive,
                        LocalDate dateFrom, LocalDate dateTo, String q, String sort, boolean ascendant, List<UUID> ids, int page, int taille) {
    }

    public record Resultat(List<Operation> operations, long total) {
    }

    public Resultat page(Query q) {
        UUID sortie = q.compteSortie() == null ? AUCUN_COMPTE : q.compteSortie();
        UUID entree = q.compteEntree() == null ? AUCUN_COMPTE : q.compteEntree();
        List<Object> params = new ArrayList<>(List.of(sortie, entree, q.entrepriseId(), q.debut(), q.fin(), sortie, entree, q.entrepriseId(), q.periodeId() + ":", q.entrepriseId()));
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if ("SALES".equalsIgnoreCase(q.side())) {
            where.append(" and c.side in ('OUTPUT', 'UNDETERMINED')");
        } else if ("PURCHASES".equalsIgnoreCase(q.side())) {
            where.append(" and c.side in ('INPUT', 'UNDETERMINED')");
        }
        if (q.treatment() != null && !q.treatment().isBlank()) {
            where.append(" and c.effective_treatment = ?");
            params.add(q.treatment().trim().toUpperCase());
        }
        if (q.aRevoir() != null) {
            where.append(q.aRevoir() ? " and (c.effective_treatment = 'TO_REVIEW' or c.has_finding)" : " and not (c.effective_treatment = 'TO_REVIEW' or c.has_finding)");
        }
        if (q.findingActive()) {
            where.append(" and c.has_finding");
        }
        if (q.dateFrom() != null) {
            where.append(" and c.date_ecriture >= ?");
            params.add(q.dateFrom());
        }
        if (q.dateTo() != null) {
            where.append(" and c.date_ecriture <= ?");
            params.add(q.dateTo());
        }
        if (q.ids() != null && !q.ids().isEmpty()) {
            where.append(" and c.id in (").append(String.join(",", Collections.nCopies(q.ids().size(), "?"))).append(")");
            params.addAll(q.ids());
        }
        if (q.q() != null && !q.q().isBlank()) {
            String recherche = q.q().trim();
            String motif = "%" + recherche.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" and (c.numero_piece ilike ? or c.libelle ilike ? or c.document ilike ? or c.tiers ilike ? or c.id::text = ?");
            params.add(motif);
            params.add(motif);
            params.add(motif);
            params.add(motif);
            params.add(recherche.toLowerCase());
            if (NOMBRE.matcher(recherche).matches()) {
                BigDecimal montant = new BigDecimal(recherche.replace(',', '.')).abs();
                where.append(" or c.total_debit = ? or abs(c.out_vat + c.in_vat) = ?");
                params.add(montant);
                params.add(montant);
            }
            where.append(")");
        }
        String cle = switch (q.sort() == null ? "date" : q.sort().toLowerCase()) {
            case "document" -> "c.document";
            case "amount" -> "c.total_debit";
            case "status" -> "(case when c.effective_treatment = 'TO_REVIEW' or c.has_finding then 0 else 1 end)";
            case "tiers" -> "c.tiers";
            default -> "c.date_ecriture";
        };
        String sens = q.ascendant() ? "asc" : "desc";
        int taille = Math.max(1, q.taille());
        params.add(taille);
        params.add((long) Math.max(0, q.page()) * taille);
        String sql = """
                with ops as (
                    select e.id, e.date_ecriture, e.numero_piece, e.libelle, j.code journal,
                           coalesce(sum(l.credit - l.debit) filter (where l.compte_id = ?), 0) out_vat,
                           coalesce(sum(l.debit - l.credit) filter (where l.compte_id = ?), 0) in_vat,
                           coalesce(sum(l.debit), 0) total_debit,
                           (array_agg(t.nom order by l.numero_ligne) filter (where t.id is not null))[1] tiers,
                           coalesce(f.numero, fa.numero, e.numero_piece) document, f.id facture_id
                    from ecriture_comptable e
                    join ligne_ecriture l on l.ecriture_id = e.id
                    join journal j on j.id = e.journal_id
                    left join tiers t on t.id = l.tiers_id
                    left join facture f on f.ecriture_id = e.id
                    left join facture_avoir fa on fa.ecriture_id = e.id
                    where e.entreprise_id = ? and e.statut = 'VALIDEE' and e.date_ecriture between ? and ?
                    group by e.id, j.code, f.id, f.numero, fa.numero
                    having bool_or(l.compte_id = ?) or bool_or(l.compte_id = ?)
                ), c as (
                    select o.*, case when o.out_vat <> 0 and o.in_vat = 0 then 'OUTPUT' when o.in_vat <> 0 and o.out_vat = 0 then 'INPUT' else 'UNDETERMINED' end side,
                           coalesce(d.treatment, case when o.out_vat <> 0 and o.in_vat = 0 then 'OUTPUT_TAX' when o.in_vat <> 0 and o.out_vat = 0 then 'INPUT_TAX' else 'TO_REVIEW' end) effective_treatment,
                           exists (select 1 from finding fi where fi.entreprise_id = ? and fi.source_type = 'TAX_OPERATION' and fi.source_id = ? || o.id and fi.status in ('OPEN', 'IN_PROGRESS')) has_finding
                    from ops o left join tax_operation_treatment d on d.entreprise_id = ? and d.ecriture_id = o.id
                )
                select c.id, c.date_ecriture, c.numero_piece, c.libelle, c.journal, c.out_vat, c.in_vat, c.total_debit, c.tiers, c.document,
                       (select sum(lf.quantite * lf.prix_unitaire) from ligne_facture lf where lf.facture_id = c.facture_id) invoice_base, count(*) over() total
                from c
                """ + where + " order by " + cle + " " + sens + " nulls last, c.date_ecriture, c.id limit ? offset ?";
        long[] total = {0};
        List<Operation> operations = jdbc.query(sql, (rs, i) -> {
            total[0] = rs.getLong("total");
            return new Operation(rs.getObject("id", UUID.class), rs.getObject("date_ecriture", LocalDate.class), rs.getString("numero_piece"), rs.getString("libelle"), rs.getString("journal"),
                    rs.getString("tiers"), rs.getBigDecimal("out_vat"), rs.getBigDecimal("in_vat"), rs.getBigDecimal("total_debit"), rs.getString("document"), rs.getBigDecimal("invoice_base"));
        }, params.toArray());
        return new Resultat(operations, total[0]);
    }

    /** Constats fiscaux actifs d'un ensemble d'opérations d'une période : écriture → codes (lecture bornée à la page affichée). */
    public Map<UUID, List<String>> constatsActifs(UUID entrepriseId, UUID periodeId, List<UUID> ecritureIds) {
        Map<UUID, List<String>> resultat = new HashMap<>();
        if (ecritureIds.isEmpty()) {
            return resultat;
        }
        List<Object> params = new ArrayList<>(List.of(entrepriseId));
        for (UUID id : ecritureIds) {
            params.add(periodeId + ":" + id);
        }
        jdbc.query("select source_id, control_code from finding where entreprise_id = ? and source_type = 'TAX_OPERATION' and status in ('OPEN', 'IN_PROGRESS') and source_id in ("
                + String.join(",", Collections.nCopies(ecritureIds.size(), "?")) + ")", rs -> {
            String source = rs.getString("source_id");
            resultat.computeIfAbsent(UUID.fromString(source.substring(source.indexOf(':') + 1)), k -> new ArrayList<>()).add(rs.getString("control_code"));
        }, params.toArray());
        return resultat;
    }

    /** Décisions humaines d'un ensemble d'écritures (lecture bornée à la page affichée). */
    public Map<UUID, VatModel.Decision> decisions(UUID entrepriseId, List<UUID> ecritureIds) {
        Map<UUID, VatModel.Decision> resultat = new HashMap<>();
        if (ecritureIds.isEmpty()) {
            return resultat;
        }
        List<Object> params = new ArrayList<>(List.of(entrepriseId));
        params.addAll(ecritureIds);
        jdbc.query("select ecriture_id, treatment, deductible_percent, reason from tax_operation_treatment where entreprise_id = ? and ecriture_id in ("
                + String.join(",", Collections.nCopies(ecritureIds.size(), "?")) + ")", rs -> {
            resultat.put(rs.getObject("ecriture_id", UUID.class), new VatModel.Decision(com.example.comptable.fiscalite.core.TaxTreatment.valueOf(rs.getString("treatment")), rs.getBigDecimal("deductible_percent"),
                    rs.getString("reason")));
        }, params.toArray());
        return resultat;
    }
}
