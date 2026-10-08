package com.example.comptable.fiscalite.vat;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.dossier.DossierRoles;
import com.example.comptable.fiscalite.vat.VatDtos.CreditRequest;
import com.example.comptable.fiscalite.vat.VatDtos.CreditView;
import com.example.comptable.fiscalite.vat.VatDtos.PaymentRequest;
import com.example.comptable.fiscalite.vat.VatDtos.PaymentSummary;
import com.example.comptable.fiscalite.vat.VatDtos.PaymentView;
import com.example.comptable.fiscalite.vat.VatDtos.ReverseRequest;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Suivi INTERNE du règlement d'une déclaration TVA finalisée (montant déclaré, payé, restant) et crédit d'ouverture saisi. Aucune règle fiscale : le montant dû est le solde déclaré du snapshot
 * FINAL ; aucun télépaiement, aucune API de l'administration. Un paiement n'est jamais supprimé (annulation tracée) ; un appel rejoué (même clé d'idempotence) ne double jamais le montant payé.
 * Le crédit d'ouverture est une donnée saisie et justifiée : il n'est jamais déduit d'une règle, jamais repris automatiquement de la période précédente, et reste « à revoir » tant qu'aucun cadre
 * VERIFIED ne prévoit le report.
 */
@Service
public class VatSettlementService {

    private static final java.util.Set<String> SOURCES = java.util.Set.of("MANUAL", "BANK_STATEMENT", "RECEIPT", "OTHER");

    private final JdbcTemplate jdbc;
    private final EntrepriseAccessService acces;
    private final AuditTrailService audit;

    public VatSettlementService(JdbcTemplate jdbc, EntrepriseAccessService acces, AuditTrailService audit) {
        this.jdbc = jdbc;
        this.acces = acces;
        this.audit = audit;
    }

    private record PeriodeRef(UUID id, UUID tenantId, String statut, long version, LocalDate debut) {
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ lecture

    @Transactional(readOnly = true)
    public PaymentSummary paiements(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        acces.verifierAcces(entrepriseId, principal);
        PeriodeRef p = periode(periodeId, entrepriseId, false);
        return resume(p.id(), p.statut().equals("FINAL") ? soldeDeclare(p.id()) : null);
    }

    /** Résumé de règlement d'une période : le montant déclaré n'existe qu'une fois FINAL (solde du snapshot). */
    public PaymentSummary resume(UUID periodeId, BigDecimal soldeDeclare) {
        List<PaymentView> paiements = jdbc.query("select id, amount, payment_date, reference, source, status, created_at, reversed_at, reverse_reason from tax_payment where tax_period_id = ? order by created_at, id",
                (rs, i) -> new PaymentView(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getObject("payment_date", LocalDate.class), rs.getString("reference"), rs.getString("source"),
                        rs.getString("status"), horodatage(rs, "created_at"), horodatage(rs, "reversed_at"), rs.getString("reverse_reason")), periodeId);
        BigDecimal paye = paiements.stream().filter(p -> "ACTIVE".equals(p.status())).map(PaymentView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal du = soldeDeclare != null && soldeDeclare.signum() > 0 ? soldeDeclare : BigDecimal.ZERO;
        BigDecimal reste = du.subtract(paye).max(BigDecimal.ZERO);
        String statut = soldeDeclare == null ? "NON_FINALISE" : du.signum() == 0 ? "AUCUN_MONTANT_DU" : paye.signum() == 0 ? "A_PAYER" : reste.signum() == 0 ? "PAYE" : "PARTIELLEMENT_PAYE";
        return new PaymentSummary(soldeDeclare == null ? null : du, paye, soldeDeclare == null ? null : reste, statut, soldeDeclare != null && reste.signum() > 0, paiements);
    }

    /** Total des paiements actifs d'une période. */
    public BigDecimal totalPaye(UUID periodeId) {
        BigDecimal t = jdbc.queryForObject("select coalesce(sum(amount), 0) from tax_payment where tax_period_id = ? and status = 'ACTIVE'", BigDecimal.class, periodeId);
        return t == null ? BigDecimal.ZERO : t;
    }

    /** Solde déclaré du snapshot FINAL (positif : TVA due ; négatif : crédit) ; {@code null} si aucun snapshot. */
    public BigDecimal soldeDeclare(UUID periodeId) {
        List<BigDecimal> l = jdbc.query("select (payload::jsonb -> 'totals' ->> 'netDeclared')::numeric v from tax_declaration_snapshot where tax_period_id = ?", (rs, i) -> rs.getBigDecimal("v"), periodeId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Le cadre de la période est VERIFIED ET prévoit le report d'un crédit. */
    public boolean reportPrevu(UUID periodeId) {
        Boolean b = jdbc.queryForObject("select coalesce(f.status = 'VERIFIED' and coalesce((f.rules ->> 'carryForward')::boolean, false), false) from tax_period p left join tax_framework f on f.id = p.framework_id where p.id = ?",
                Boolean.class, periodeId);
        return Boolean.TRUE.equals(b);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ paiements

    @Transactional
    public PaymentSummary enregistrer(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, PaymentRequest req) {
        exigerPreparation(principal);
        acces.verifierAcces(entrepriseId, principal);
        PeriodeRef p = periode(periodeId, entrepriseId, true);
        verifierVersion(p, req == null ? null : req.expectedVersion());
        if (!"FINAL".equals(p.statut())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un paiement ne peut être enregistré que pour une déclaration finalisée");
        }
        if (req == null || req.amount() == null || req.amount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le montant d'un paiement est strictement positif");
        }
        if (req.paymentDate() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "La date du paiement est obligatoire");
        }
        String cle = req.idempotencyKey() == null ? "" : req.idempotencyKey().trim();
        if (cle.isEmpty() || cle.length() > 100) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Une clé d'idempotence (1 à 100 caractères) est obligatoire");
        }
        String source = req.source() == null ? "" : req.source().trim().toUpperCase();
        if (!SOURCES.contains(source)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Source du paiement inconnue : " + SOURCES);
        }
        BigDecimal montant = req.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        List<Map<String, Object>> existant = jdbc.queryForList("select id, amount from tax_payment where tax_period_id = ? and idempotency_key = ?", periodeId, cle);
        if (!existant.isEmpty()) {
            if (((BigDecimal) existant.get(0).get("amount")).compareTo(montant) != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette clé d'idempotence a déjà été utilisée pour un autre montant");
            }
            return resume(periodeId, soldeDeclare(periodeId));                                   // rejeu : aucun doublon, même résultat
        }
        BigDecimal solde = soldeDeclare(periodeId);
        if (solde == null || solde.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucune TVA due : il n'y a rien à payer pour cette période");
        }
        BigDecimal reste = solde.subtract(totalPaye(periodeId));
        if (montant.compareTo(reste) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le paiement dépasse le reste à payer (" + reste.toPlainString() + ")");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tax_payment (id, tenant_id, entreprise_id, tax_period_id, amount, payment_date, reference, source, idempotency_key, status, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, now())",
                id, p.tenantId(), entrepriseId, periodeId, montant, req.paymentDate(), req.reference() == null || req.reference().isBlank() ? null : req.reference().trim(), source, cle, principal.getId());
        bump(periodeId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periode", periodeId.toString());
        details.put("montant", montant.toPlainString());
        details.put("source", source);
        details.put("reference", req.reference());
        audit.enregistrerActionHumaine(p.tenantId(), principal.getId(), "PAIEMENT_FISCAL_ENREGISTRE", "PAIEMENT_FISCAL", id.toString(), details);
        return resume(periodeId, solde);
    }

    @Transactional
    public PaymentSummary annuler(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, UUID paiementId, ReverseRequest req) {
        exigerPreparation(principal);
        acces.verifierAcces(entrepriseId, principal);
        PeriodeRef p = periode(periodeId, entrepriseId, true);
        verifierVersion(p, req == null ? null : req.expectedVersion());
        if (req == null || req.reason() == null || req.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le motif d'annulation est obligatoire");
        }
        int n = jdbc.update("update tax_payment set status = 'REVERSED', reversed_by = ?, reversed_at = now(), reverse_reason = ? where id = ? and tax_period_id = ? and status = 'ACTIVE'", principal.getId(),
                req.reason().trim(), paiementId, periodeId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Paiement introuvable ou déjà annulé");
        }
        bump(periodeId);
        audit.enregistrerActionHumaine(p.tenantId(), principal.getId(), "PAIEMENT_FISCAL_ANNULE", "PAIEMENT_FISCAL", paiementId.toString(), Map.of("periode", periodeId.toString(), "motif", req.reason().trim()));
        return resume(periodeId, soldeDeclare(periodeId));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ crédit d'ouverture

    @Transactional(readOnly = true)
    public CreditView credit(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        acces.verifierAcces(entrepriseId, principal);
        PeriodeRef p = periode(periodeId, entrepriseId, false);
        return creditView(p.id(), p.statut().equals("FINAL") ? soldeDeclare(p.id()) : null);
    }

    /** Vue du crédit : ouverture saisie (jamais déduite), clôture informative (solde négatif), état « à revoir » sans règle de report vérifiée. */
    public CreditView creditView(UUID periodeId, BigDecimal soldeDeclare) {
        List<CreditView> l = jdbc.query("select amount, reason, source, previous_period_id, created_at from tax_opening_credit where tax_period_id = ? and superseded_at is null",
                (rs, i) -> new CreditView(rs.getBigDecimal("amount"), rs.getString("reason"), rs.getString("source"), rs.getObject("previous_period_id", UUID.class), horodatage(rs, "created_at"), null, null, null), periodeId);
        boolean report = reportPrevu(periodeId);
        BigDecimal cloture = soldeDeclare != null && soldeDeclare.signum() < 0 ? soldeDeclare.negate() : null;
        String etatOuverture = l.isEmpty() ? "NONE" : report ? "VALIDATED" : "TO_REVIEW";
        String etatCloture = cloture == null ? "NONE" : report ? "REPORTABLE" : "TO_REVIEW";
        CreditView base = l.isEmpty() ? new CreditView(null, null, null, null, null, null, null, null) : l.get(0);
        return new CreditView(base.openingAmount(), base.reason(), base.source(), base.previousPeriodId(), base.createdAt(), etatOuverture, cloture, etatCloture);
    }

    /** Crédit d'ouverture à valider : bloque la finalisation tant qu'aucun cadre vérifié ne prévoit le report. */
    public boolean creditOuvertureAValider(UUID periodeId) {
        Integer n = jdbc.queryForObject("select count(*) from tax_opening_credit where tax_period_id = ? and superseded_at is null", Integer.class, periodeId);
        return n != null && n > 0 && !reportPrevu(periodeId);
    }

    @Transactional
    public CreditView saisirCredit(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, CreditRequest req) {
        exigerPreparation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        PeriodeRef p = periode(periodeId, entrepriseId, true);
        verifierVersion(p, req == null ? null : req.expectedVersion());
        if ("FINAL".equals(p.statut())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette période est finalisée : le crédit d'ouverture ne peut plus être modifié");
        }
        if (req == null || req.amount() == null || req.amount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le crédit d'ouverture est un montant strictement positif");
        }
        if (req.reason() == null || req.reason().isBlank() || req.source() == null || req.source().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Une justification et une source sont obligatoires");
        }
        if (req.previousPeriodId() != null) {
            Integer n = jdbc.queryForObject("select count(*) from tax_period where id = ? and entreprise_id = ? and tax_type = 'TVA' and period_end < ?", Integer.class, req.previousPeriodId(), entrepriseId, p.debut());
            if (n == null || n == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Période précédente introuvable");
            }
        }
        boolean modification = jdbc.update("update tax_opening_credit set superseded_at = now(), superseded_by = ? where tax_period_id = ? and superseded_at is null", principal.getId(), periodeId) > 0;
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tax_opening_credit (id, tenant_id, entreprise_id, tax_period_id, amount, reason, source, previous_period_id, status, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, 'TO_REVIEW', ?, now())",
                id, e.getTenant().getId(), entrepriseId, periodeId, req.amount().setScale(2, java.math.RoundingMode.HALF_UP), req.reason().trim(), req.source().trim(), req.previousPeriodId(), principal.getId());
        bump(periodeId);
        // une saisie après préparation invalide la revue : le crédit fait partie des éléments à contrôler
        jdbc.update("update tax_period set status = 'PREPARING', updated_at = now() where id = ? and status in ('READY_FOR_REVIEW', 'REVIEWED')", periodeId);
        jdbc.update("update tax_declaration set status = 'DRAFT', reviewed_by = null, reviewed_at = null where tax_period_id = ? and status in ('READY', 'REVIEWED')", periodeId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periode", periodeId.toString());
        details.put("montant", req.amount().toPlainString());
        details.put("source", req.source().trim());
        details.put("justification", req.reason().trim());
        audit.enregistrerActionHumaine(p.tenantId(), principal.getId(), modification ? "CREDIT_OUVERTURE_MODIFIE" : "CREDIT_OUVERTURE_SAISI", "PERIODE_FISCALE", periodeId.toString(), details);
        return creditView(periodeId, null);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ outils

    private PeriodeRef periode(UUID id, UUID entrepriseId, boolean verrou) {
        List<PeriodeRef> l = jdbc.query("select p.id, p.tenant_id, p.status, p.version, p.period_start from tax_period p where p.id = ? and p.entreprise_id = ?"
                + (verrou ? " for update" : ""), (rs, i) -> new PeriodeRef(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("status"), rs.getLong("version"),
                rs.getObject("period_start", LocalDate.class)), id, entrepriseId);
        return l.stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Période fiscale introuvable"));
    }

    private static void verifierVersion(PeriodeRef p, Long attendue) {
        if (attendue != null && attendue != p.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette période a été modifiée par un autre utilisateur (version courante " + p.version() + ", version fournie " + attendue + "). Rechargez avant de poursuivre.");
        }
    }

    private void bump(UUID periodeId) {
        PeriodVersion.bumpOnce(jdbc, periodeId);
    }

    private static void exigerPreparation(UtilisateurPrincipal p) {
        if (!DossierRoles.peutPreparer(p)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Réservé aux collaborateurs et experts-comptables du cabinet");
        }
    }

    private static OffsetDateTime horodatage(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
