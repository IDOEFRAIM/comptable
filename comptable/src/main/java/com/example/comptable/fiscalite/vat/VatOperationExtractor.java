package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.vat.VatModel.Operation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Extrait de la comptabilité les opérations de TVA d'une période : UNE requête pour toutes les écritures validées qui touchent un compte de TVA (jamais une requête par opération), plus une
 * requête de contrôle sur le mouvement du grand livre. Les comptes de TVA sont ceux configurés par l'entreprise (purposes), jamais un numéro supposé. La comptabilité n'est ni lue ligne à ligne ni modifiée :
 * une opération est agrégée en SQL.
 */
@Component
public class VatOperationExtractor {

    private static final UUID AUCUN_COMPTE = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;

    public VatOperationExtractor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Operation> extraire(UUID entrepriseId, LocalDate debut, LocalDate fin, UUID compteSortie, UUID compteEntree) {
        UUID sortie = compteSortie == null ? AUCUN_COMPTE : compteSortie;
        UUID entree = compteEntree == null ? AUCUN_COMPTE : compteEntree;
        return jdbc.query("""
                select e.id, e.date_ecriture, e.numero_piece, e.libelle, j.code journal,
                       coalesce(sum(l.credit - l.debit) filter (where l.compte_id = ?), 0) out_vat,
                       coalesce(sum(l.debit - l.credit) filter (where l.compte_id = ?), 0) in_vat,
                       coalesce(sum(l.debit), 0) total_debit,
                       (array_agg(t.nom order by l.numero_ligne) filter (where t.id is not null))[1] tiers,
                       coalesce(f.numero, fa.numero, e.numero_piece) document,
                       (select sum(lf.quantite * lf.prix_unitaire) from ligne_facture lf where lf.facture_id = f.id) invoice_base
                from ecriture_comptable e
                join ligne_ecriture l on l.ecriture_id = e.id
                join journal j on j.id = e.journal_id
                left join tiers t on t.id = l.tiers_id
                left join facture f on f.ecriture_id = e.id
                left join facture_avoir fa on fa.ecriture_id = e.id
                where e.entreprise_id = ? and e.statut = 'VALIDEE' and e.date_ecriture between ? and ?
                group by e.id, j.code, f.id, f.numero, fa.numero
                having bool_or(l.compte_id = ?) or bool_or(l.compte_id = ?)
                order by e.date_ecriture, e.numero_piece nulls last, e.id
                """, (rs, i) -> new Operation(rs.getObject("id", UUID.class), rs.getObject("date_ecriture", LocalDate.class), rs.getString("numero_piece"), rs.getString("libelle"), rs.getString("journal"),
                rs.getString("tiers"), rs.getBigDecimal("out_vat"), rs.getBigDecimal("in_vat"), rs.getBigDecimal("total_debit"), rs.getString("document"), rs.getBigDecimal("invoice_base")),
                sortie, entree, entrepriseId, debut, fin, sortie, entree);
    }

    /** Mouvement net du grand livre sur un compte de TVA, période incluse : {@code credit − debit} (sens = true) ou {@code debit − credit}. */
    public BigDecimal mouvement(UUID entrepriseId, UUID compteId, LocalDate debut, LocalDate fin, boolean creditMoinsDebit) {
        if (compteId == null) {
            return null;
        }
        BigDecimal m = jdbc.queryForObject("""
                select coalesce(sum(%s), 0) from ligne_ecriture l join ecriture_comptable e on e.id = l.ecriture_id
                where e.entreprise_id = ? and e.statut = 'VALIDEE' and l.compte_id = ? and e.date_ecriture between ? and ?
                """.formatted(creditMoinsDebit ? "l.credit - l.debit" : "l.debit - l.credit"), BigDecimal.class, entrepriseId, compteId, debut, fin);
        return m == null ? BigDecimal.ZERO : m;
    }
}
