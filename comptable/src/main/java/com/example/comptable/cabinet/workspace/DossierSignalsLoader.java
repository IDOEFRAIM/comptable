package com.example.comptable.cabinet.workspace;

import com.example.comptable.fiscalite.core.TaxPeriodStatus;
import com.example.comptable.fiscalite.vat.VatOperationalStatusEvaluator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Charge, par requêtes GROUPÉES et bornées (une par source, quel que soit le nombre de dossiers : jamais de N+1), les faits de tout le portefeuille d'un cabinet. Les constats, demandes, documents et
 * la banque sont rattachés à l'entreprise : ils alimentent le dossier courant (l'exercice ouvert le plus ancien, sinon le plus récent) ; l'écriture, les régularisations, les notes et le snapshot sont propres à chaque exercice.
 * Chaque requête est bornée par le tenant : un cabinet ne voit jamais les dossiers d'un autre.
 */
@Component
public class DossierSignalsLoader {

    private static final String DEMANDE_EN_ATTENTE_CLIENT = "('EN_ATTENTE','A_ENVOYER','ECHEC_ENVOI')";
    private static final String FINDING_ACTIF = "('OPEN','IN_PROGRESS')";
    /** Les constats fiscaux (TVA) ont leur propre avancement (colonne TVA du portefeuille, file « À traiter ») : ils ne changent pas le statut comptable du dossier. */
    private static final String HORS_FISCAL_SQL = "f.source_type in ('TAX_OPERATION', 'TAX_PERIOD')";
    private static final String BLOQUANT = "(f.severity = 'CRITICAL' or f.control_severity = 'BLOCKING')";
    /** Un constat dont la pièce a déjà été demandée au client n'est plus une action du cabinet : on attend le client. */
    private static final String ATTEND_LE_CLIENT = "(exists (select 1 from demande_piece d where d.finding_id = f.id and d.statut in " + DEMANDE_EN_ATTENTE_CLIENT + ") or " + HORS_FISCAL_SQL + ")";

    private final JdbcTemplate jdbc;

    public DossierSignalsLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DossierRow(UUID entrepriseId, String entrepriseNom, String identifiant, UUID exerciceId, LocalDate debut, LocalDate fin, boolean exerciceCloture, boolean courant,
                             UUID collaborateurId, String collaborateurNom, UUID expertId, String expertNom) {
    }

    /** Un dossier et ses faits bruts, prêts pour l'évaluateur. */
    public record DossierCharge(DossierRow dossier, DossierSignals signaux, OffsetDateTime derniereActivite, String tva) {
    }

    @Transactional(readOnly = true)
    public List<DossierCharge> charger(UUID tenantId) {
        List<DossierRow> dossiers = jdbc.query("""
                select e.id eid, e.raison_sociale, e.numero_contribuable, x.id xid, x.date_debut, x.date_fin, x.statut,
                       x.id = (select x2.id from exercice_comptable x2 where x2.entreprise_id = e.id order by (x2.statut = 'OUVERT') desc, (case when x2.statut = 'OUVERT' then x2.date_fin end) asc nulls last, x2.date_fin desc limit 1) courant,
                       a.collaborateur_id, trim(coalesce(uc.nom, '') || ' ' || coalesce(uc.prenom, '')) collab_nom,
                       a.expert_id, trim(coalesce(ue.nom, '') || ' ' || coalesce(ue.prenom, '')) expert_nom
                from entreprise e
                join exercice_comptable x on x.entreprise_id = e.id
                left join dossier_affectation a on a.entreprise_id = e.id and a.exercice_id = x.id
                left join utilisateur uc on uc.id = a.collaborateur_id
                left join utilisateur ue on ue.id = a.expert_id
                where e.tenant_id = ? and e.statut = 'ACTIF'
                order by e.raison_sociale, x.date_fin desc
                """, (rs, i) -> new DossierRow(uuid(rs, "eid"), rs.getString("raison_sociale"), rs.getString("numero_contribuable"), uuid(rs, "xid"),
                rs.getObject("date_debut", LocalDate.class), rs.getObject("date_fin", LocalDate.class), "CLOTURE".equals(rs.getString("statut")), rs.getBoolean("courant"),
                uuid(rs, "collaborateur_id"), blankToNull(rs.getString("collab_nom")), uuid(rs, "expert_id"), blankToNull(rs.getString("expert_nom"))), tenantId);
        if (dossiers.isEmpty()) {
            return List.of();
        }

        // ---- par exercice
        Map<UUID, long[]> ecritures = new HashMap<>();             // [validees, brouillon]
        Map<UUID, OffsetDateTime> ecritureActivite = new HashMap<>();
        jdbc.query("""
                select ec.exercice_id, count(*) filter (where ec.statut = 'VALIDEE') validees, count(*) filter (where ec.statut = 'BROUILLON') brouillon, max(ec.created_at) derniere
                from ecriture_comptable ec join entreprise e on e.id = ec.entreprise_id where e.tenant_id = ? group by ec.exercice_id
                """, rs -> {
            ecritures.put(uuid(rs, "exercice_id"), new long[] {rs.getLong("validees"), rs.getLong("brouillon")});
            ecritureActivite.put(uuid(rs, "exercice_id"), odt(rs, "derniere"));
        }, tenantId);
        Map<UUID, Long> regularisations = new HashMap<>();
        jdbc.query("""
                select r.exercice_id, count(*) n from regularisation r join entreprise e on e.id = r.entreprise_id
                where e.tenant_id = ? and r.statut in ('BROUILLON', 'PROPOSEE') group by r.exercice_id
                """, rs -> {
            regularisations.put(uuid(rs, "exercice_id"), rs.getLong("n"));
        }, tenantId);
        Map<UUID, long[]> notes = new HashMap<>();                 // [total, aDecider, incompletes, revisables, revues]
        jdbc.query("""
                select n.exercice_id, count(*) total,
                       count(*) filter (where n.applicability_status in ('TO_DETERMINE', 'NOT_APPLICABLE_CANDIDATE')) a_decider,
                       count(*) filter (where n.applicability_status = 'APPLICABLE' and n.completion_status in ('INCOMPLETE', 'MANUAL_REQUIRED')) incompletes,
                       count(*) filter (where n.applicability_status = 'APPLICABLE' and n.completion_status = 'READY') revisables,
                       count(*) filter (where n.applicability_status = 'APPLICABLE' and n.completion_status = 'READY' and n.reviewed_at is not null) revues
                from financial_statement_note n join entreprise e on e.id = n.entreprise_id where e.tenant_id = ? group by n.exercice_id
                """, rs -> {
            notes.put(uuid(rs, "exercice_id"), new long[] {rs.getLong("total"), rs.getLong("a_decider"), rs.getLong("incompletes"), rs.getLong("revisables"), rs.getLong("revues")});
        }, tenantId);
        Map<UUID, Boolean> finalises = new HashMap<>();
        jdbc.query("""
                select distinct s.exercice_id from financial_statement_snapshot s join entreprise e on e.id = s.entreprise_id
                where e.tenant_id = ? and s.statement_type = 'DOSSIER_COMPLET' and s.status = 'FINAL'
                """, rs -> {
            finalises.put(uuid(rs, "exercice_id"), true);
        }, tenantId);

        // ---- par entreprise
        Map<UUID, long[]> findings = new HashMap<>();              // [internes, bloquants, importants, reconciliation, total tous statuts]
        Map<UUID, OffsetDateTime> findingAncien = new HashMap<>();
        Map<UUID, OffsetDateTime> findingActivite = new HashMap<>();
        jdbc.query("""
                select f.entreprise_id,
                       count(*) filter (where f.status in %s and not %s) internes,
                       count(*) filter (where f.status in %s and not %s and %s) bloquants,
                       count(*) filter (where f.status in %s and not %s and not %s and f.severity = 'IMPORTANT') importants,
                       count(*) filter (where f.status in %s and not %s and f.category = 'RECONCILIATION') reconciliation,
                       count(*) total,
                       min(f.created_at) filter (where f.status in %s and not %s) plus_ancien,
                       max(coalesce(f.resolved_at, f.created_at)) derniere
                from finding f join entreprise e on e.id = f.entreprise_id where e.tenant_id = ? group by f.entreprise_id
                """.formatted(FINDING_ACTIF, ATTEND_LE_CLIENT, FINDING_ACTIF, ATTEND_LE_CLIENT, BLOQUANT, FINDING_ACTIF, ATTEND_LE_CLIENT, BLOQUANT, FINDING_ACTIF, ATTEND_LE_CLIENT,
                FINDING_ACTIF, ATTEND_LE_CLIENT), rs -> {
            UUID id = uuid(rs, "entreprise_id");
            findings.put(id, new long[] {rs.getLong("internes"), rs.getLong("bloquants"), rs.getLong("importants"), rs.getLong("reconciliation"), rs.getLong("total")});
            findingAncien.put(id, odt(rs, "plus_ancien"));
            findingActivite.put(id, odt(rs, "derniere"));
        }, tenantId);
        Map<UUID, long[]> demandes = new HashMap<>();              // [attenteClient, enRetard, recues, total]
        Map<UUID, OffsetDateTime> demandeAncienne = new HashMap<>();
        Map<UUID, OffsetDateTime> demandeActivite = new HashMap<>();
        jdbc.query("""
                select d.entreprise_id,
                       count(*) filter (where d.statut in %s) attente,
                       count(*) filter (where d.statut in %s and d.date_limite < current_date) retard,
                       count(*) filter (where d.statut = 'RECU') recues,
                       count(*) total,
                       min(d.created_at) filter (where d.statut in %s) ancienne,
                       max(d.created_at) derniere
                from demande_piece d join entreprise e on e.id = d.entreprise_id where e.tenant_id = ? group by d.entreprise_id
                """.formatted(DEMANDE_EN_ATTENTE_CLIENT, DEMANDE_EN_ATTENTE_CLIENT, DEMANDE_EN_ATTENTE_CLIENT), rs -> {
            UUID id = uuid(rs, "entreprise_id");
            demandes.put(id, new long[] {rs.getLong("attente"), rs.getLong("retard"), rs.getLong("recues"), rs.getLong("total")});
            demandeAncienne.put(id, odt(rs, "ancienne"));
            demandeActivite.put(id, odt(rs, "derniere"));
        }, tenantId);
        Map<UUID, long[]> documents = new HashMap<>();             // [aTraiter, total]
        Map<UUID, OffsetDateTime> documentActivite = new HashMap<>();
        jdbc.query("""
                select d.entreprise_id,
                       count(*) filter (where d.statut in ('TRAITE', 'ERREUR', 'EN_ATTENTE_VALIDATION_HUMAINE') and d.ecriture_id is null) a_traiter,
                       count(*) total, max(d.created_at) derniere
                from document d join entreprise e on e.id = d.entreprise_id where e.tenant_id = ? group by d.entreprise_id
                """, rs -> {
            UUID id = uuid(rs, "entreprise_id");
            documents.put(id, new long[] {rs.getLong("a_traiter"), rs.getLong("total")});
            documentActivite.put(id, odt(rs, "derniere"));
        }, tenantId);
        Map<UUID, long[]> banque = new HashMap<>();                // [nonRapprochees, total, propositions]
        jdbc.query("""
                select l.entreprise_id, count(*) total,
                       count(*) filter (where l.ligne_ecriture_id is null and coalesce(l.duplicate_status, 'NONE') = 'NONE'
                           and not exists (select 1 from bank_reconciliation r where r.bank_transaction_id = l.id and r.status = 'CONFIRMED')
                           and not exists (select 1 from bank_internal_transfer t where t.status = 'CONFIRMED' and (t.debit_transaction_id = l.id or t.credit_transaction_id = l.id))) non_rapprochees
                from ligne_releve l join entreprise e on e.id = l.entreprise_id where e.tenant_id = ? group by l.entreprise_id
                """, rs -> {
            banque.put(uuid(rs, "entreprise_id"), new long[] {rs.getLong("non_rapprochees"), rs.getLong("total"), 0});
        }, tenantId);
        jdbc.query("""
                select r.entreprise_id, count(*) n from bank_reconciliation r join entreprise e on e.id = r.entreprise_id
                where e.tenant_id = ? and r.status = 'PROPOSED' group by r.entreprise_id
                """, rs -> {
            banque.computeIfAbsent(uuid(rs, "entreprise_id"), k -> new long[3])[2] = rs.getLong("n");
        }, tenantId);

        // statut TVA du dossier : la première période non finalisée, sinon la dernière finalisée ; un seul code compact calculé par l'évaluateur unique (jamais « finalisable » sans cadre vérifié)
        Map<UUID, String> tva = new HashMap<>();
        jdbc.query("""
                select distinct on (p.entreprise_id) p.entreprise_id, p.status,
                       coalesce(f.status = 'VERIFIED', false) verifie,
                       coalesce(f.status = 'VERIFIED' and coalesce((f.rules ->> 'carryForward')::boolean, false), false) report,
                       (select count(*) from finding fi where fi.entreprise_id = p.entreprise_id and fi.source_type = 'TAX_OPERATION' and fi.status in ('OPEN', 'IN_PROGRESS') and fi.source_id like p.id || ':%') a_traiter,
                       (select (s.payload::jsonb -> 'totals' ->> 'netDeclared')::numeric from tax_declaration_snapshot s where s.tax_period_id = p.id) net,
                       (select coalesce(sum(tp.amount), 0) from tax_payment tp where tp.tax_period_id = p.id and tp.status = 'ACTIVE') paye
                from tax_period p left join tax_framework f on f.id = p.framework_id where p.tenant_id = ? and p.tax_type = 'TVA'
                order by p.entreprise_id, (p.status = 'FINAL'), (case when p.status <> 'FINAL' then p.period_end end) asc nulls last, p.period_end desc
                """, rs -> {
            int aTraiter = rs.getInt("a_traiter");
            String code = VatOperationalStatusEvaluator.codePortefeuille(TaxPeriodStatus.valueOf(rs.getString("status")), rs.getBoolean("verifie"), aTraiter, rs.getBigDecimal("net"), rs.getBigDecimal("paye"),
                    rs.getBoolean("report"));
            tva.put(uuid(rs, "entreprise_id"), "A_TRAITER".equals(code) ? code + ":" + aTraiter : code);
        }, tenantId);

        LocalDate aujourdhui = LocalDate.now();
        List<DossierCharge> resultat = new ArrayList<>(dossiers.size());
        for (DossierRow d : dossiers) {
            DossierSignals.DossierSignalsBuilder b = DossierSignals.builder().exerciceCloture(d.exerciceCloture()).finalise(finalises.containsKey(d.exerciceId()));
            long[] ec = ecritures.getOrDefault(d.exerciceId(), new long[2]);
            long[] nt = notes.getOrDefault(d.exerciceId(), new long[5]);
            b.ecrituresValidees(ec[0]).ecrituresBrouillon(ec[1]).regularisationsNonFinalisees(regularisations.getOrDefault(d.exerciceId(), 0L))
                    .notesTotal(nt[0]).notesADecider(nt[1]).notesIncompletes(nt[2]).notesRevisables(nt[3]).notesRevues(nt[4]);
            OffsetDateTime derniere = ecritureActivite.get(d.exerciceId());
            boolean activite = ec[0] + ec[1] + nt[0] > 0;
            if (d.courant()) {
                UUID e = d.entrepriseId();
                long[] fi = findings.getOrDefault(e, new long[5]);
                long[] de = demandes.getOrDefault(e, new long[4]);
                long[] dc = documents.getOrDefault(e, new long[2]);
                long[] ba = banque.getOrDefault(e, new long[3]);
                b.findingsInternes(fi[0]).findingsBloquants(fi[1]).findingsImportants(fi[2]).banqueAmbigues(fi[3])
                        .demandesAttenteClient(de[0]).demandesEnRetard(de[1]).piecesRecuesARattacher(de[2])
                        .documentsATraiter(dc[0]).banqueNonRapprochees(ba[0]).banquePropositions(ba[2]);
                activite = activite || fi[4] + de[3] + dc[1] + ba[1] > 0;
                OffsetDateTime plusAncien = plusAncien(findingAncien.get(e), demandeAncienne.get(e));
                b.joursPlusAncienItem(plusAncien == null ? 0 : java.time.Duration.between(plusAncien, OffsetDateTime.now()).toDays());
                derniere = plusRecent(plusRecent(derniere, findingActivite.get(e)), plusRecent(demandeActivite.get(e), documentActivite.get(e)));
            }
            b.activiteConnue(activite);
            resultat.add(new DossierCharge(d, b.build(), derniere, d.courant() ? tva.get(d.entrepriseId()) : null));
        }
        return resultat;
    }

    private static OffsetDateTime plusAncien(OffsetDateTime a, OffsetDateTime b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static OffsetDateTime plusRecent(OffsetDateTime a, OffsetDateTime b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    /** Lecture tolérante : timestamp avec ou sans fuseau. */
    static OffsetDateTime odt(ResultSet rs, String colonne) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(colonne);
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    static UUID uuid(ResultSet rs, String colonne) throws SQLException {
        return rs.getObject(colonne, UUID.class);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
