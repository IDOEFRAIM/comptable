package com.example.comptable.fiscalite.vat;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlResultPublisher;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.dossier.DossierRoles;
import com.example.comptable.fiscalite.core.TaxDeadlineGenerator;
import com.example.comptable.fiscalite.core.TaxFrameworkService;
import com.example.comptable.fiscalite.core.TaxFrameworkService.Resolution;
import com.example.comptable.fiscalite.core.TaxFrameworkService.TaxFramework;
import com.example.comptable.fiscalite.core.TaxPeriodStatus;
import com.example.comptable.fiscalite.core.TaxTreatment;
import com.example.comptable.fiscalite.core.VatRules;
import com.example.comptable.fiscalite.vat.VatDtos.*;
import com.example.comptable.fiscalite.vat.VatModel.Adjustment;
import com.example.comptable.fiscalite.vat.VatModel.Anomaly;
import com.example.comptable.fiscalite.vat.VatModel.Computation;
import com.example.comptable.fiscalite.vat.VatModel.Decision;
import com.example.comptable.fiscalite.vat.VatModel.Line;
import com.example.comptable.fiscalite.vat.VatModel.Operation;
import com.example.comptable.security.UtilisateurPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Espace TVA : périodes déclaratives, classification fiscale, ajustements, contrôles (Findings), déclaration, revue et finalisation avec snapshot immuable. La TVA est CALCULÉE à partir de la
 * comptabilité (aucune écriture n'est jamais modifiée) avec les règles d'un cadre fiscal : sans cadre VERIFIED, les montants comptabilisés restent visibles mais aucune déclaration ne peut être finalisée.
 */
@Service
public class VatWorkspaceService {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Set<String> CODES_FISCAUX = Set.of(ControlCodes.TAX_TREATMENT_MISSING, ControlCodes.TAX_RATE_UNRESOLVED, ControlCodes.VAT_BASE_MISMATCH, ControlCodes.VAT_AMOUNT_MISMATCH,
            ControlCodes.DUPLICATE_TAX_DOCUMENT, ControlCodes.TAX_PERIOD_INCOMPLETE, ControlCodes.TAX_FRAMEWORK_NOT_VERIFIED);
    private static final int TAILLE_MAX = 200;
    private static final int BULK_MAX = 500;

    record Periode(UUID id, UUID tenantId, UUID entrepriseId, LocalDate debut, LocalDate fin, TaxPeriodStatus statut, UUID frameworkId, String frameworkVersion, long version) {
    }

    private record Calcul(Entreprise entreprise, Periode periode, Resolution resolution, Computation computation, List<Anomaly> anomalies) {
        List<Anomaly> bloquantsPreparation() {
            return anomalies.stream().filter(Anomaly::bloquePreparation).toList();
        }
    }

    private final JdbcTemplate jdbc;
    private final EntrepriseAccessService acces;
    private final EntrepriseRepository entrepriseRepository;
    private final TaxFrameworkService cadres;
    private final VatOperationExtractor extracteur;
    private final VatEngine moteur;
    private final AccountingAccountResolver resolveur;
    private final ControlResultPublisher publisher;
    private final FindingRepository findingRepository;
    private final AuditTrailService audit;
    private final VatOperationPageQuery pages;
    private final VatSettlementService reglement;
    private final java.time.Clock clock;
    private final TransactionTemplate nouvelleTransaction;

    public VatWorkspaceService(JdbcTemplate jdbc, EntrepriseAccessService acces, EntrepriseRepository entrepriseRepository, TaxFrameworkService cadres, VatOperationExtractor extracteur,
                               VatEngine moteur, AccountingAccountResolver resolveur, ControlResultPublisher publisher, FindingRepository findingRepository, AuditTrailService audit,
                               VatOperationPageQuery pages, VatSettlementService reglement, java.time.Clock clock, PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.acces = acces;
        this.entrepriseRepository = entrepriseRepository;
        this.cadres = cadres;
        this.extracteur = extracteur;
        this.moteur = moteur;
        this.resolveur = resolveur;
        this.publisher = publisher;
        this.findingRepository = findingRepository;
        this.audit = audit;
        this.pages = pages;
        this.reglement = reglement;
        this.nouvelleTransaction = new TransactionTemplate(transactionManager);
        this.nouvelleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ périodes

    @Transactional(readOnly = true)
    public List<PeriodSummary> lister(UtilisateurPrincipal principal, UUID entrepriseId, String statut, String frameworkVersion) {
        acces.verifierAcces(entrepriseId, principal);
        List<Object> params = new ArrayList<>(List.of(entrepriseId));
        StringBuilder filtres = new StringBuilder();
        if (statut != null && !statut.isBlank()) {
            filtres.append(" and p.status = ?");
            params.add(statut.trim().toUpperCase());
        }
        if (frameworkVersion != null && !frameworkVersion.isBlank()) {
            filtres.append(" and p.framework_version = ?");
            params.add(frameworkVersion.trim());
        }
        return jdbc.query("select p.id, p.period_start, p.period_end, p.status, p.framework_version, p.version, coalesce(f.status = 'VERIFIED', false) verifie from tax_period p left join tax_framework f on f.id = p.framework_id "
                + "where p.entreprise_id = ? and p.tax_type = 'TVA'" + filtres + " order by p.period_start desc", (rs, i) -> new PeriodSummary(rs.getObject("id", UUID.class), rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class), rs.getString("status"), rs.getString("framework_version"), rs.getBoolean("verifie"), rs.getLong("version")), params.toArray());
    }

    @Transactional
    public PeriodView ouvrir(UtilisateurPrincipal principal, UUID entrepriseId, OpenRequest req) {
        exigerPreparation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        if (req.periodStart() == null || req.periodEnd() == null || req.periodEnd().isBefore(req.periodStart())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Période invalide : la fin précède le début");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(req.periodStart(), req.periodEnd()) > 400) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Une période déclarative ne peut pas dépasser quatorze mois");
        }
        Integer chevauche = jdbc.queryForObject("select count(*) from tax_period where entreprise_id = ? and tax_type = 'TVA' and period_start <= ? and period_end >= ?", Integer.class, entrepriseId,
                req.periodEnd(), req.periodStart());
        if (chevauche != null && chevauche > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette période chevauche une période TVA existante");
        }
        Resolution res = cadres.resoudre(e.getTenant().getId(), e.getPays(), TaxFrameworkService.TVA, req.periodEnd()).orElse(null);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tax_period (id, tenant_id, entreprise_id, tax_type, period_start, period_end, status, framework_id, framework_version, created_at, updated_at) values (?, ?, ?, 'TVA', ?, ?, 'OPEN', ?, ?, now(), now())",
                id, e.getTenant().getId(), entrepriseId, req.periodStart(), req.periodEnd(), res == null ? null : res.framework().id(), res == null ? null : res.framework().version());
        creerEcheance(e, id, req.periodStart(), req.periodEnd(), res);
        synchroniser(e, periode(id, entrepriseId), false);                                  // les anomalies des opérations existantes sont publiées dès l'ouverture ; le statut reste OPEN
        audit.enregistrerActionHumaine(e.getTenant().getId(), principal.getId(), "PERIODE_FISCALE_OUVERTE", "PERIODE_FISCALE", id.toString(), Map.of("debut", req.periodStart().toString(), "fin", req.periodEnd().toString()));
        return vue(principal, e, periode(id, entrepriseId));
    }

    /** L'échéance vient UNIQUEMENT de la règle du cadre fiscal VÉRIFIÉ : sans règle d'échéance vérifiée, aucune date n'est inventée. */
    private void creerEcheance(Entreprise e, UUID periodeId, LocalDate debut, LocalDate fin, Resolution res) {
        if (res == null || !res.verifie() || res.framework().rules().due() == null) {
            return;
        }
        LocalDate date = TaxDeadlineGenerator.echeance(res.framework().rules(), fin);
        jdbc.update("insert into dossier_echeance (id, tenant_id, entreprise_id, exercice_id, type, libelle, date_echeance, statut, created_at, updated_at) values (?, ?, ?, null, 'DECLARATION_FISCALE', ?, ?, 'OUVERTE', now(), now())",
                UUID.randomUUID(), e.getTenant().getId(), e.getId(), libelleEcheance(debut, fin), date);
    }

    private static String libelleEcheance(LocalDate debut, LocalDate fin) {
        return "Déclaration TVA " + debut + " → " + fin;
    }

    @Transactional(readOnly = true)
    public PeriodView detail(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        return vue(principal, e, periode(periodeId, entrepriseId));
    }

    private PeriodView vue(UtilisateurPrincipal principal, Entreprise e, Periode p) {
        Permissions perms = new Permissions(DossierRoles.peutPreparer(principal), DossierRoles.peutRevoir(principal), DossierRoles.peutFinaliser(principal));
        LocalDate echeance = echeance(p);
        if (p.statut() == TaxPeriodStatus.FINAL) {
            Map<String, Object> figé = payloadFinal(p.id());
            Computation c = lireCalculFinal(figé);
            VatSettlementService r = reglement;
            VatOperationalStatusEvaluator.Result etat = VatOperationalStatusEvaluator.evaluer(new VatOperationalStatusEvaluator.Input(p.statut(), true, 0, 0, true, BigDecimal.ZERO, c.totals().netDeclared(),
                    r.totalPaye(p.id()), r.reportPrevu(p.id()), false, c.totals().operations()));
            return new PeriodView(p.id(), p.entrepriseId(), p.debut(), p.fin(), p.statut().name(), cadreFinal(figé), c.totals(), c.reconciliation(), c.basis(), c.officialStructure(), 0, 0, false, List.of(), perms, true,
                    p.version(), true, List.of(), progression(etat), r.resume(p.id(), c.totals().netDeclared()), r.creditView(p.id(), c.totals().netDeclared()), echeance);
        }
        Calcul calcul = calculer(e, p);
        List<String> bloqueurs = bloqueursFinalisation(calcul, false);
        boolean finalisable = p.statut() == TaxPeriodStatus.REVIEWED && bloqueurs.isEmpty();
        VatOperationalStatusEvaluator.Result etat = VatOperationalStatusEvaluator.evaluer(new VatOperationalStatusEvaluator.Input(p.statut(), calcul.resolution() != null && calcul.resolution().verifie(),
                calcul.computation().totals().toReview(), (int) calcul.bloquantsPreparation().stream().filter(a -> a.ecritureId() == null).count(), calcul.computation().totals().fiscalComplete(),
                calcul.computation().totals().unexplained(), null, BigDecimal.ZERO, reglement.reportPrevu(p.id()), finalisable, calcul.computation().totals().operations()));
        return new PeriodView(p.id(), p.entrepriseId(), p.debut(), p.fin(), p.statut().name(), info(calcul.resolution()), calcul.computation().totals(), calcul.computation().reconciliation(), calcul.computation().basis(),
                calcul.computation().officialStructure(), calcul.anomalies().size(), calcul.computation().totals().toReview(), finalisable, bloqueurs, perms, false, p.version(),
                calcul.resolution() != null && calcul.resolution().verifie(), raisonsBlocage(calcul), progression(etat), reglement.resume(p.id(), null), reglement.creditView(p.id(), null), echeance);
    }

    private static Progress progression(VatOperationalStatusEvaluator.Result r) {
        return new Progress(r.status(), r.readySteps(), r.totalSteps(), r.blockingSteps(), r.steps());
    }

    /** Date limite d'après la règle du cadre VERIFIED de la période ; {@code null} sans règle vérifiée (jamais une date supposée). */
    private LocalDate echeance(Periode p) {
        if (p.frameworkId() == null) {
            return null;
        }
        return cadres.parId(p.frameworkId()).filter(TaxFramework::verifie).map(f -> TaxDeadlineGenerator.echeance(f.rules(), p.fin())).orElse(null);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ opérations

    /** Filtres de la liste des opérations : tous évalués en SQL (aucun ne dépend d'une règle fiscale). */
    public record OperationFilters(String cote, String statut, String treatment, boolean findingActive, LocalDate dateFrom, LocalDate dateTo, String q, String sort, boolean ascendant) {
    }

    /**
     * Liste paginée côté base : seule la page demandée est lue (le moteur ne calcule que ces lignes). Une période FINAL se relit depuis son snapshot (borné, immuable).
     * {@code cote} accepte SALES / PURCHASES (ou OUTPUT / INPUT) ; {@code statut} : TO_REVIEW ou OK.
     */
    @Transactional(readOnly = true)
    public Page<Line> operations(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, OperationFilters f, int page, int taille) {
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = periode(periodeId, entrepriseId);
        int t = Math.max(1, Math.min(taille, TAILLE_MAX));
        int pg = Math.max(0, page);
        if (p.statut() == TaxPeriodStatus.FINAL) {
            return operationsFigees(lireCalculFinal(payloadFinal(p.id())).lines(), f, pg, t);
        }
        Boolean aRevoir = f.statut() == null || f.statut().isBlank() || "ALL".equalsIgnoreCase(f.statut()) ? null : "TO_REVIEW".equalsIgnoreCase(f.statut());
        var res = pages.page(requete(e, p, f, aRevoir, null, pg, t));
        return new Page<>(lignes(e, p, res.operations()), pg, t, res.total());
    }

    private VatOperationPageQuery.Query requete(Entreprise e, Periode p, OperationFilters f, Boolean aRevoir, List<UUID> ids, int page, int taille) {
        String cote = f.cote() == null ? null : "OUTPUT".equalsIgnoreCase(f.cote()) ? "SALES" : "INPUT".equalsIgnoreCase(f.cote()) ? "PURCHASES" : f.cote();
        return new VatOperationPageQuery.Query(e.getId(), p.id(), p.debut(), p.fin(), compteTva(e.getId(), AccountPurpose.TVA_COLLECTEE), compteTva(e.getId(), AccountPurpose.TVA_RECUPERABLE), cote, f.treatment(), aRevoir,
                f.findingActive(), f.dateFrom(), f.dateTo(), f.q(), f.sort(), f.ascendant(), ids, page, taille);
    }

    /** Calcule les lignes des seules opérations données (la page), avec les constats actifs de la période (le doublon documentaire se voit sur l'ensemble de la période). */
    private List<Line> lignes(Entreprise e, Periode p, List<Operation> operations) {
        if (operations.isEmpty()) {
            return List.of();
        }
        Resolution res = cadres.resoudre(e.getTenant().getId(), e.getPays(), TaxFrameworkService.TVA, p.fin()).orElse(null);
        List<UUID> ids = operations.stream().map(Operation::ecritureId).toList();
        Computation c = moteur.calculer(operations, pages.decisions(e.getId(), ids), ajustementsActifs(p.id()), res == null ? null : res.framework().rules(), res != null && res.verifie(), null, null);
        Map<UUID, List<String>> constats = pages.constatsActifs(e.getId(), p.id(), ids);
        List<Line> lignes = new ArrayList<>(c.lines().size());
        for (Line l : c.lines()) {
            List<String> codes = constats.getOrDefault(l.ecritureId(), List.of());
            if (codes.isEmpty() || l.issues().containsAll(codes)) {
                lignes.add(l);
                continue;
            }
            List<String> issues = new ArrayList<>(l.issues());
            codes.stream().filter(x -> !issues.contains(x)).forEach(issues::add);
            lignes.add(new Line(l.ecritureId(), l.date(), l.piece(), l.document(), l.tiers(), l.libelle(), l.side(), l.treatment(), l.treatmentSource(), l.base(), l.impliedRatePercent(), l.rateCode(), l.vatAccounted(),
                    l.vatFiscal(), l.vatDeductible(), l.ecart(), l.explication(), "TO_REVIEW", issues));
        }
        return lignes;
    }

    private static Page<Line> operationsFigees(List<Line> toutes, OperationFilters f, int page, int taille) {
        String q = f.q() == null ? "" : f.q().trim().toLowerCase();
        List<Line> filtrees = toutes.stream()
                .filter(l -> f.cote() == null || f.cote().isBlank() || l.side().equals("UNDETERMINED") || l.side().equalsIgnoreCase("SALES".equalsIgnoreCase(f.cote()) ? "OUTPUT" : "PURCHASES".equalsIgnoreCase(f.cote()) ? "INPUT" : f.cote()))
                .filter(l -> f.statut() == null || f.statut().isBlank() || "ALL".equalsIgnoreCase(f.statut()) || f.statut().equalsIgnoreCase(l.status()))
                .filter(l -> f.treatment() == null || f.treatment().isBlank() || l.treatment().name().equalsIgnoreCase(f.treatment()))
                .filter(l -> f.dateFrom() == null || !l.date().isBefore(f.dateFrom())).filter(l -> f.dateTo() == null || !l.date().isAfter(f.dateTo()))
                .filter(l -> q.isEmpty() || (String.valueOf(l.piece()) + " " + l.document() + " " + l.tiers() + " " + l.libelle() + " " + l.ecritureId()).toLowerCase().contains(q)).toList();
        int debut = Math.min(page * taille, filtrees.size());
        return new Page<>(filtrees.subList(debut, Math.min(debut + taille, filtrees.size())), page, taille, filtrees.size());
    }

    private List<Adjustment> ajustementsActifs(UUID periodeId) {
        return jdbc.query("select id, kind, effect, amount, ecriture_id from tax_adjustment where tax_period_id = ? and cancelled_at is null",
                (rs, i) -> new Adjustment(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("effect"), rs.getBigDecimal("amount"), rs.getObject("ecriture_id", UUID.class)), periodeId);
    }


    // Surcharges sans jeton de version : appels internes et tests (le contrôle de version n'est appliqué que lorsqu'un expectedVersion est fourni).
    @Transactional(readOnly = true)
    public Page<Line> operations(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, String cote, String statut, int page, int taille) {
        return operations(principal, entrepriseId, periodeId, new OperationFilters(cote, statut, null, false, null, null, null, "date", true), page, taille);
    }

    @Transactional
    public PeriodView recalculer(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        return recalculer(principal, entrepriseId, periodeId, null);
    }

    @Transactional
    public PeriodView preparer(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        return preparer(principal, entrepriseId, periodeId, null);
    }

    @Transactional
    public PeriodView revoir(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        return revoir(principal, entrepriseId, periodeId, null);
    }

    @Transactional
    public PeriodView rouvrir(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        return rouvrir(principal, entrepriseId, periodeId, null);
    }

    @Transactional
    public PeriodView finaliser(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        return finaliser(principal, entrepriseId, periodeId, null);
    }

    @Transactional(readOnly = true)
    public List<PeriodSummary> lister(UtilisateurPrincipal principal, UUID entrepriseId) {
        return lister(principal, entrepriseId, null, null);
    }

    @Transactional(readOnly = true)
    public ControlsView controles(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = periode(periodeId, entrepriseId);
        if (p.statut() == TaxPeriodStatus.FINAL) {
            Computation c = lireCalculFinal(payloadFinal(p.id()));
            return new ControlsView(List.of(), c.reconciliation(), c.totals().unexplained());
        }
        Calcul calcul = calculer(e, p);
        return new ControlsView(calcul.anomalies(), calcul.computation().reconciliation(), calcul.computation().totals().unexplained());
    }

    /** Décision fiscale sur une opération : traitement + justification obligatoire ; l'écriture comptable n'est jamais modifiée. */
    @Transactional
    public PeriodView decider(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, UUID ecritureId, TreatmentRequest req) {
        exigerPreparation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = verrouiller(periodeId, entrepriseId, req.expectedVersion(), true);
        exigerModifiable(p);
        TaxTreatment traitement;
        try {
            traitement = TaxTreatment.valueOf(req.treatment() == null ? "" : req.treatment().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Traitement fiscal inconnu : " + req.treatment());
        }
        if (traitement == TaxTreatment.TO_REVIEW) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "TO_REVIEW n'est pas une décision : choisissez un traitement");
        }
        if (req.reason() == null || req.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Une justification est obligatoire");
        }
        if (traitement == TaxTreatment.PARTIALLY_DEDUCTIBLE && (req.deductiblePercent() == null || req.deductiblePercent().signum() <= 0 || req.deductiblePercent().compareTo(new BigDecimal("100")) >= 0)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "La part déductible est un pourcentage strictement entre 0 et 100");
        }
        Line ligne = lignes(e, p, pages.page(requete(e, p, new OperationFilters(null, null, null, false, null, null, null, null, true), null, List.of(ecritureId), 0, 1)).operations()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cette écriture n'est pas une opération de TVA de la période"));
        boolean sortie = "OUTPUT".equals(ligne.side());
        boolean entree = "INPUT".equals(ligne.side());
        if ((sortie && (traitement == TaxTreatment.INPUT_TAX || traitement == TaxTreatment.NON_DEDUCTIBLE || traitement == TaxTreatment.PARTIALLY_DEDUCTIBLE))
                || (entree && traitement == TaxTreatment.OUTPUT_TAX)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Ce traitement est incompatible avec le sens de la TVA comptabilisée sur cette écriture");
        }
        String avant = ligne.treatmentSource().equals("DECISION") ? ligne.treatment().name() : null;
        BigDecimal part = traitement == TaxTreatment.PARTIALLY_DEDUCTIBLE ? req.deductiblePercent() : null;
        jdbc.update("""
                insert into tax_operation_treatment (id, tenant_id, entreprise_id, ecriture_id, treatment, deductible_percent, reason, decided_by, decided_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (entreprise_id, ecriture_id) do update set treatment = excluded.treatment, deductible_percent = excluded.deductible_percent, reason = excluded.reason,
                    decided_by = excluded.decided_by, decided_at = excluded.decided_at
                """, UUID.randomUUID(), p.tenantId(), entrepriseId, ecritureId, traitement.name(), part, req.reason().trim(), principal.getId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periode", periodeId.toString());
        details.put("ecriture", ecritureId.toString());
        details.put("avant", avant);
        details.put("apres", traitement.name());
        details.put("justification", req.reason().trim());
        audit.enregistrerActionHumaine(p.tenantId(), principal.getId(), "TRAITEMENT_FISCAL_MODIFIE", "PERIODE_FISCALE", periodeId.toString(), details);
        return recalculerInterne(principal, e, periodeId);
    }

    /**
     * Validation en masse d'un traitement CERTAIN : le serveur revalide chaque opération (même période, non finalisée, même sens de TVA comptabilisée, traitement demandé égal à celui que ce sens impose,
     * aucune décision humaine contraire, aucun constat de conflit). Un seul refus annule tout (tout ou rien) : aucune sélection n'impose jamais un traitement que le serveur ne peut pas démontrer.
     */
    @Transactional
    public BulkTreatmentResult traiterEnMasse(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, BulkTreatmentRequest req) {
        exigerPreparation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = verrouiller(periodeId, entrepriseId, req == null ? null : req.expectedVersion(), true);
        exigerModifiable(p);
        if (req == null || req.ecritureIds() == null || req.ecritureIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucune opération sélectionnée");
        }
        List<UUID> ids = req.ecritureIds().stream().distinct().toList();
        if (ids.size() > BULK_MAX) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Une validation en masse porte sur " + BULK_MAX + " opérations au plus");
        }
        TaxTreatment demande;
        try {
            demande = TaxTreatment.valueOf(req.treatment() == null ? "" : req.treatment().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Traitement fiscal inconnu : " + req.treatment());
        }
        if (demande != TaxTreatment.OUTPUT_TAX && demande != TaxTreatment.INPUT_TAX) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Seuls OUTPUT_TAX et INPUT_TAX, déduits du sens de la TVA comptabilisée, se valident en masse ; tout autre traitement se décide opération par opération");
        }
        if (req.reason() == null || req.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Une justification est obligatoire");
        }
        List<Operation> operations = pages.page(requete(e, p, new OperationFilters(null, null, null, false, null, null, null, null, true), null, ids, 0, ids.size())).operations();
        Map<UUID, Operation> parId = new HashMap<>();
        operations.forEach(o -> parId.put(o.ecritureId(), o));
        Map<UUID, Decision> decisions = pages.decisions(e.getId(), ids);
        Map<UUID, List<String>> constats = pages.constatsActifs(e.getId(), p.id(), ids);
        List<BulkRejection> refus = new ArrayList<>();
        for (UUID id : ids) {
            Operation o = parId.get(id);
            if (o == null) {
                refus.add(new BulkRejection(id, "opération introuvable dans cette période"));
                continue;
            }
            TaxTreatment propose = o.outputVat().signum() != 0 && o.inputVat().signum() == 0 ? TaxTreatment.OUTPUT_TAX : o.inputVat().signum() != 0 && o.outputVat().signum() == 0 ? TaxTreatment.INPUT_TAX : null;
            if (propose == null) {
                refus.add(new BulkRejection(id, "sens de TVA non déterminable : décision individuelle requise"));
            } else if (propose != demande) {
                refus.add(new BulkRejection(id, "le sens de la TVA comptabilisée impose " + propose + ", pas " + demande));
            } else if (decisions.containsKey(id) && decisions.get(id).treatment() != demande) {
                refus.add(new BulkRejection(id, "une décision humaine différente existe (" + decisions.get(id).treatment() + ")"));
            } else if (constats.getOrDefault(id, List.of()).stream().anyMatch(c -> c.equals(ControlCodes.DUPLICATE_TAX_DOCUMENT) || c.equals(ControlCodes.VAT_BASE_MISMATCH))) {
                refus.add(new BulkRejection(id, "constat actif en conflit (doublon ou base de TVA incohérente) : à traiter individuellement"));
            }
        }
        if (!refus.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Validation en masse refusée : " + refus.size() + " opération(s) incompatible(s) sur " + ids.size() + " — "
                    + refus.stream().limit(5).map(r -> r.ecritureId() + " (" + r.reason() + ")").collect(Collectors.joining(" ; ")));
        }
        jdbc.batchUpdate("""
                insert into tax_operation_treatment (id, tenant_id, entreprise_id, ecriture_id, treatment, deductible_percent, reason, decided_by, decided_at)
                values (?, ?, ?, ?, ?, null, ?, ?, now())
                on conflict (entreprise_id, ecriture_id) do update set treatment = excluded.treatment, deductible_percent = null, reason = excluded.reason, decided_by = excluded.decided_by, decided_at = excluded.decided_at
                """, ids.stream().map(id -> new Object[]{UUID.randomUUID(), p.tenantId(), entrepriseId, id, demande.name(), req.reason().trim(), principal.getId()}).toList());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periode", periodeId.toString());
        details.put("traitement", demande.name());
        details.put("nombre", ids.size());
        details.put("justification", req.reason().trim());
        details.put("ecritures", ids.stream().limit(20).map(UUID::toString).toList());
        audit.enregistrerActionHumaine(p.tenantId(), principal.getId(), "TRAITEMENT_FISCAL_BULK_MODIFIE", "PERIODE_FISCALE", periodeId.toString(), details);
        return new BulkTreatmentResult(ids.size(), recalculerInterne(principal, e, periodeId));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ ajustements

    @Transactional(readOnly = true)
    public List<AdjustmentView> ajustements(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        acces.verifierAcces(entrepriseId, principal);
        periode(periodeId, entrepriseId);
        return jdbc.query("select id, kind, effect, amount, reason, evidence, ecriture_id, created_at, cancelled_at, cancel_reason from tax_adjustment where tax_period_id = ? order by created_at",
                (rs, i) -> new AdjustmentView(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("effect"), rs.getBigDecimal("amount"), rs.getString("reason"), rs.getString("evidence"),
                        rs.getObject("ecriture_id", UUID.class), horodatage(rs, "created_at"), rs.getTimestamp("cancelled_at") != null, rs.getString("cancel_reason")), periodeId);
    }

    @Transactional
    public PeriodView ajouterAjustement(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, AdjustmentRequest req) {
        exigerPreparation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = verrouiller(periodeId, entrepriseId, req.expectedVersion(), true);
        exigerModifiable(p);
        String genre = req.kind() == null ? "" : req.kind().trim().toUpperCase();
        String effet = req.effect() == null ? "" : req.effect().trim().toUpperCase();
        if (!Set.of("CORRECTION", "REGULARISATION", "PRORATA", "NON_DEDUCTIBILITY", "ROUNDING", "MANUAL").contains(genre)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Genre d'ajustement inconnu : " + req.kind());
        }
        if (!Set.of("OUTPUT", "INPUT").contains(effet)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'effet est OUTPUT (TVA collectée) ou INPUT (TVA déductible)");
        }
        if (req.amount() == null || req.amount().signum() == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le montant d'un ajustement ne peut pas être nul");
        }
        if (req.reason() == null || req.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Une justification est obligatoire");
        }
        if (req.ecritureId() != null) {
            Integer existe = jdbc.queryForObject("select count(*) from ecriture_comptable where id = ? and entreprise_id = ?", Integer.class, req.ecritureId(), entrepriseId);
            if (existe == null || existe == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Écriture introuvable");
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tax_adjustment (id, tenant_id, entreprise_id, tax_period_id, kind, effect, amount, reason, evidence, ecriture_id, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                id, p.tenantId(), entrepriseId, periodeId, genre, effet, req.amount(), req.reason().trim(), req.evidence() == null || req.evidence().isBlank() ? null : req.evidence().trim(), req.ecritureId(),
                principal.getId());
        audit.enregistrerActionHumaine(p.tenantId(), principal.getId(), "AJUSTEMENT_FISCAL_AJOUTE", "PERIODE_FISCALE", periodeId.toString(),
                Map.of("ajustement", id.toString(), "genre", genre, "effet", effet, "montant", req.amount().toPlainString(), "justification", req.reason().trim()));
        return recalculerInterne(principal, e, periodeId);
    }

    @Transactional
    public PeriodView annulerAjustement(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, UUID ajustementId, CancelRequest req) {
        exigerPreparation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = verrouiller(periodeId, entrepriseId, req == null ? null : req.expectedVersion(), true);
        exigerModifiable(p);
        if (req == null || req.reason() == null || req.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le motif d'annulation est obligatoire");
        }
        int n = jdbc.update("update tax_adjustment set cancelled_at = now(), cancelled_by = ?, cancel_reason = ? where id = ? and tax_period_id = ? and cancelled_at is null", principal.getId(), req.reason().trim(),
                ajustementId, periodeId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ajustement introuvable ou déjà annulé");
        }
        audit.enregistrerActionHumaine(p.tenantId(), principal.getId(), "AJUSTEMENT_FISCAL_ANNULE", "PERIODE_FISCALE", periodeId.toString(), Map.of("ajustement", ajustementId.toString(), "motif", req.reason().trim()));
        return recalculerInterne(principal, e, periodeId);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ recalcul et cycle

    /** Recalcul explicite : idempotent (mêmes opérations → mêmes constats, aucun doublon). */
    @Transactional
    public PeriodView recalculer(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, Long expectedVersion) {
        exigerPreparation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        verrouiller(periodeId, entrepriseId, expectedVersion, false);
        return recalculerInterne(principal, e, periodeId);
    }

    private PeriodView recalculerInterne(UtilisateurPrincipal principal, Entreprise e, UUID periodeId) {
        Periode p = periode(periodeId, e.getId());
        if (p.statut() != TaxPeriodStatus.FINAL) {
            synchroniser(e, p);
        }
        return vue(principal, e, periode(periodeId, e.getId()));
    }

    /** Préparation terminée (collaborateur ou expert) : la période passe à READY_FOR_REVIEW seulement si rien ne reste à revoir. */
    @Transactional
    public PeriodView preparer(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, Long expectedVersion) {
        exigerPreparation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = verrouiller(periodeId, entrepriseId, expectedVersion, true);
        exigerModifiable(p);
        Calcul calcul = synchroniser(e, p);
        List<String> bloqueurs = bloqueursPreparation(calcul);
        if (!bloqueurs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La période n'est pas prête pour la revue : " + String.join(" ; ", bloqueurs));
        }
        changerStatut(p.id(), TaxPeriodStatus.READY_FOR_REVIEW);
        auditerStatut(principal, p, TaxPeriodStatus.READY_FOR_REVIEW);
        jdbc.update("update tax_declaration set status = 'READY', updated_at = now() where tax_period_id = ?", p.id());
        return vue(principal, e, periode(periodeId, entrepriseId));
    }

    @Transactional
    public PeriodView revoir(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, Long expectedVersion) {
        exigerRevue(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = verrouiller(periodeId, entrepriseId, expectedVersion, true);
        if (p.statut() != TaxPeriodStatus.READY_FOR_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une période READY_FOR_REVIEW peut être revue (statut actuel : " + p.statut() + ")");
        }
        Calcul calcul = synchroniser(e, p);
        List<String> bloqueurs = bloqueursPreparation(calcul);
        if (!bloqueurs.isEmpty() || periode(periodeId, entrepriseId).statut() != TaxPeriodStatus.READY_FOR_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La période a changé depuis sa préparation : " + String.join(" ; ", bloqueurs));
        }
        changerStatut(p.id(), TaxPeriodStatus.REVIEWED);
        auditerStatut(principal, p, TaxPeriodStatus.REVIEWED);
        jdbc.update("update tax_declaration set status = 'REVIEWED', reviewed_by = ?, reviewed_at = now(), updated_at = now() where tax_period_id = ?", principal.getId(), p.id());
        audit.enregistrerActionHumaine(p.tenantId(), principal.getId(), "DECLARATION_FISCALE_REVUE", "PERIODE_FISCALE", periodeId.toString(), Map.of("cadre", String.valueOf(p.frameworkVersion())));
        return vue(principal, e, periode(periodeId, entrepriseId));
    }

    @Transactional
    public PeriodView rouvrir(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, Long expectedVersion) {
        exigerPreparation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = verrouiller(periodeId, entrepriseId, expectedVersion, true);
        exigerModifiable(p);
        if (p.statut() == TaxPeriodStatus.READY_FOR_REVIEW || p.statut() == TaxPeriodStatus.REVIEWED) {
            changerStatut(p.id(), TaxPeriodStatus.PREPARING);
            auditerStatut(principal, p, TaxPeriodStatus.PREPARING);
            jdbc.update("update tax_declaration set status = 'DRAFT', reviewed_by = null, reviewed_at = null, updated_at = now() where tax_period_id = ?", p.id());
        }
        return vue(principal, e, periode(periodeId, entrepriseId));
    }

    /** FINAL : cadre VERIFIED, période revue, aucun constat, aucun écart inexpliqué ; crée le snapshot immuable. */
    @Transactional
    public PeriodView finaliser(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, Long expectedVersion) {
        exigerFinalisation(principal);
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = verrouiller(periodeId, entrepriseId, expectedVersion, true);
        if (p.statut() == TaxPeriodStatus.FINAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette déclaration est déjà finalisée");
        }
        Calcul calcul = synchroniser(e, p);
        Periode apres = periode(periodeId, entrepriseId);
        List<String> bloqueurs = bloqueursFinalisation(calcul, true);
        if (apres.statut() != TaxPeriodStatus.REVIEWED) {
            bloqueurs = new ArrayList<>(bloqueurs);
            if (!bloqueurs.contains("revue non effectuée")) {
                bloqueurs.add("revue non effectuée");
            }
        }
        if (!bloqueurs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Finalisation impossible : " + String.join(" ; ", bloqueurs));
        }
        TaxFramework cadre = calcul.resolution().framework();
        List<AdjustmentView> ajustements = ajustements(principal, entrepriseId, periodeId);
        Map<String, Object> reviewer = jdbc.queryForMap("select reviewed_by, reviewed_at from tax_declaration where tax_period_id = ?", periodeId);
        OffsetDateTime maintenant = OffsetDateTime.now(clock);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("framework", Map.of("id", cadre.id().toString(), "jurisdiction", cadre.jurisdiction(), "version", cadre.version(), "definitionHash", cadre.definitionHash(), "sourceReferences", cadre.sourceReferences(),
                "effectiveFrom", String.valueOf(cadre.effectiveFrom()), "status", cadre.status()));
        payload.put("period", Map.of("id", periodeId.toString(), "start", p.debut().toString(), "end", p.fin().toString(), "entrepriseId", entrepriseId.toString()));
        payload.put("basis", calcul.computation().basis());
        payload.put("officialStructure", calcul.computation().officialStructure());
        payload.put("totals", calcul.computation().totals());
        payload.put("reconciliation", calcul.computation().reconciliation());
        payload.put("declaration", calcul.computation().declaration());
        payload.put("lines", calcul.computation().lines());
        payload.put("adjustments", ajustements);
        payload.put("controls", calcul.anomalies());
        payload.put("openingCredit", reglement.creditView(periodeId, null));
        payload.put("reviewer", Map.of("id", String.valueOf(reviewer.get("reviewed_by")), "at", String.valueOf(reviewer.get("reviewed_at"))));
        payload.put("finalizedBy", principal.getId().toString());
        payload.put("finalizedAt", maintenant.toString());
        String json = json(payload);
        UUID snapshotId = UUID.randomUUID();
        UUID declarationId = jdbc.queryForObject("select id from tax_declaration where tax_period_id = ?", UUID.class, periodeId);
        jdbc.update("insert into tax_declaration_snapshot (id, tenant_id, entreprise_id, tax_period_id, declaration_id, framework_id, framework_version, definition_hash, payload, payload_sha256, created_by, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())", snapshotId, p.tenantId(), entrepriseId, periodeId, declarationId, cadre.id(), cadre.version(), cadre.definitionHash(), json, sha256(json), principal.getId());
        jdbc.update("update tax_declaration set status = 'FINAL', finalized_by = ?, finalized_at = now(), snapshot_id = ?, updated_at = now() where tax_period_id = ?", principal.getId(), snapshotId, periodeId);
        changerStatut(periodeId, TaxPeriodStatus.FINAL);
        auditerStatut(principal, p, TaxPeriodStatus.FINAL);
        jdbc.update("update tax_period set framework_id = ?, framework_version = ? where id = ?", cadre.id(), cadre.version(), periodeId);
        jdbc.update("update dossier_echeance set statut = 'FAITE', updated_at = now() where entreprise_id = ? and type = 'DECLARATION_FISCALE' and libelle = ?", entrepriseId, libelleEcheance(p.debut(), p.fin()));
        resoudreTout(e, periodeId);
        audit.enregistrerActionHumaine(p.tenantId(), principal.getId(), "DECLARATION_FISCALE_FINALISEE", "PERIODE_FISCALE", periodeId.toString(), Map.of("snapshot", snapshotId.toString(), "cadre", cadre.version(),
                "sha256", sha256(json)));
        return vue(principal, e, periode(periodeId, entrepriseId));
    }

    @Transactional(readOnly = true)
    public DeclarationView declaration(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = periode(periodeId, entrepriseId);
        Map<String, Object> d = jdbc.queryForList("select id, status, reviewed_at, finalized_at, snapshot_id from tax_declaration where tax_period_id = ?", periodeId).stream().findFirst().orElse(Map.of());
        if (p.statut() == TaxPeriodStatus.FINAL) {
            Map<String, Object> figé = payloadFinal(periodeId);
            Computation c = lireCalculFinal(figé);
            String sha = jdbc.queryForObject("select payload_sha256 from tax_declaration_snapshot where tax_period_id = ?", String.class, periodeId);
            return new DeclarationView((UUID) d.get("id"), "FINAL", c.basis(), c.officialStructure(), cadreFinal(figé), c.declaration(), odt(d.get("reviewed_at")), odt(d.get("finalized_at")), (UUID) d.get("snapshot_id"), sha, false, List.of(), List.of());
        }
        Calcul calcul = calculer(e, p);
        List<String> bloqueurs = bloqueursFinalisation(calcul, false);
        String statut = d.get("status") == null ? "DRAFT" : String.valueOf(d.get("status"));
        return new DeclarationView((UUID) d.get("id"), statut, calcul.computation().basis(), calcul.computation().officialStructure(), info(calcul.resolution()), calcul.computation().declaration(),
                odt(d.get("reviewed_at")), null, null, null, p.statut() == TaxPeriodStatus.REVIEWED && bloqueurs.isEmpty(), bloqueurs, raisonsBlocage(calcul));
    }

    @Transactional(readOnly = true)
    public SnapshotView snapshot(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId) {
        acces.verifierAcces(entrepriseId, principal);
        periode(periodeId, entrepriseId);
        List<Map<String, Object>> l = jdbc.queryForList("select id, created_at, payload_sha256, framework_version, payload from tax_declaration_snapshot where tax_period_id = ? and entreprise_id = ?", periodeId, entrepriseId);
        if (l.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucun snapshot : la déclaration n'est pas finalisée");
        }
        Map<String, Object> s = l.get(0);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = MAPPER.readValue((String) s.get("payload"), Map.class);
            return new SnapshotView((UUID) s.get("id"), odt(s.get("created_at")), (String) s.get("payload_sha256"), (String) s.get("framework_version"), payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Snapshot illisible", ex);
        }
    }

    /** « Pourquoi ? » : opération → lignes de l'écriture → facture → pièces. */
    @Transactional(readOnly = true)
    public LineageView origine(UtilisateurPrincipal principal, UUID entrepriseId, UUID periodeId, UUID ecritureId) {
        Entreprise e = acces.verifierAcces(entrepriseId, principal);
        Periode p = periode(periodeId, entrepriseId);
        Line ligne = (p.statut() == TaxPeriodStatus.FINAL ? lireCalculFinal(payloadFinal(periodeId)).lines() : calculer(e, p).computation().lines()).stream()
                .filter(l -> l.ecritureId().equals(ecritureId)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Opération introuvable dans cette période"));
        List<Map<String, Object>> lignes = jdbc.queryForList("""
                select c.numero compte, c.intitule, l.libelle, l.debit, l.credit from ligne_ecriture l join compte_comptable c on c.id = l.compte_id
                where l.ecriture_id = ? order by l.numero_ligne
                """, ecritureId);
        List<Map<String, Object>> factures = jdbc.queryForList("select f.numero, f.date_emission, f.statut, (select count(*) from ligne_facture lf where lf.facture_id = f.id) lignes from facture f where f.ecriture_id = ? and f.entreprise_id = ?", ecritureId, entrepriseId);
        List<Map<String, Object>> documents = jdbc.queryForList("select d.id, d.nom_fichier_original nom, d.numero_facture, d.statut from document d where d.ecriture_id = ? and d.entreprise_id = ?", ecritureId, entrepriseId);
        return new LineageView(ligne, lignes, factures.isEmpty() ? null : factures.get(0), documents);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ synchronisation proactive

    /** Appelée après le commit d'une écriture validée : recalcule les périodes TVA non finalisées qui couvrent ses dates (aucune visite d'écran nécessaire). */
    public void recalculerPeriodesCouvrant(UUID entrepriseId, Set<LocalDate> dates) {
        List<UUID> periodes = new ArrayList<>();
        for (LocalDate d : dates) {
            periodes.addAll(jdbc.queryForList("select id from tax_period where entreprise_id = ? and tax_type = 'TVA' and status <> 'FINAL' and period_start <= ? and period_end >= ?", UUID.class, entrepriseId, d, d));
        }
        for (UUID id : periodes.stream().distinct().toList()) {
            try {
                nouvelleTransaction.executeWithoutResult(s -> {
                    Entreprise e = entrepriseRepository.findById(entrepriseId).orElseThrow();
                    jdbc.queryForList("select id from tax_period where id = ? for update", id);
                    Periode p = periode(id, entrepriseId);
                    if (p.statut() != TaxPeriodStatus.FINAL) {
                        synchroniser(e, p);
                    }
                });
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(VatWorkspaceService.class).warn("Synchronisation TVA impossible (période {}) : {}", id, ex.toString());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ calcul

    private Calcul calculer(Entreprise e, Periode p) {
        Resolution res = cadres.resoudre(e.getTenant().getId(), e.getPays(), TaxFrameworkService.TVA, p.fin()).orElse(null);
        UUID sortie = compteTva(e.getId(), AccountPurpose.TVA_COLLECTEE);
        UUID entree = compteTva(e.getId(), AccountPurpose.TVA_RECUPERABLE);
        List<Operation> operations = extracteur.extraire(e.getId(), p.debut(), p.fin(), sortie, entree);
        Map<UUID, Decision> decisions = new HashMap<>();
        jdbc.query("select ecriture_id, treatment, deductible_percent, reason from tax_operation_treatment where entreprise_id = ?", rs -> {
            decisions.put(rs.getObject("ecriture_id", UUID.class), new Decision(TaxTreatment.valueOf(rs.getString("treatment")), rs.getBigDecimal("deductible_percent"), rs.getString("reason")));
        }, e.getId());
        Computation c = moteur.calculer(operations, decisions, ajustementsActifs(p.id()), res == null ? null : res.framework().rules(), res != null && res.verifie(), extracteur.mouvement(e.getId(), sortie, p.debut(), p.fin(), true),
                extracteur.mouvement(e.getId(), entree, p.debut(), p.fin(), false));
        List<Anomaly> anomalies = new ArrayList<>(c.anomalies());
        if (sortie == null) {
            anomalies.add(new Anomaly(ControlCodes.TAX_PERIOD_INCOMPLETE, null, "ACCOUNT_OUTPUT", "Compte de TVA collectée non configuré pour cette entreprise"));
        }
        if (entree == null) {
            anomalies.add(new Anomaly(ControlCodes.TAX_PERIOD_INCOMPLETE, null, "ACCOUNT_INPUT", "Compte de TVA récupérable non configuré pour cette entreprise"));
        }
        return new Calcul(e, p, res, c, anomalies);
    }

    private UUID compteTva(UUID entrepriseId, AccountPurpose but) {
        var r = resolveur.resoudreCompte(entrepriseId, but, AccountContext.vide(), null);
        return r.estResolu() ? r.compte().getId() : null;
    }

    /** Recalcule, publie / résout les Findings, met à jour la déclaration et le statut de la période (revue annulée si les données ont changé). */
    private Calcul synchroniser(Entreprise e, Periode p) {
        return synchroniser(e, p, true);
    }

    private Calcul synchroniser(Entreprise e, Periode p, boolean avancerLeStatut) {
        Calcul calcul = calculer(e, p);
        publierControles(e, p, calcul.anomalies());
        String empreinte = empreinte(calcul);
        Map<String, Object> donnees = new LinkedHashMap<>();
        donnees.put("basis", calcul.computation().basis());
        donnees.put("officialStructure", calcul.computation().officialStructure());
        donnees.put("totals", calcul.computation().totals());
        donnees.put("reconciliation", calcul.computation().reconciliation());
        donnees.put("declaration", calcul.computation().declaration());
        donnees.put("fingerprint", empreinte);
        String ancienneEmpreinte = jdbc.queryForList("select computed_data ->> 'fingerprint' from tax_declaration where tax_period_id = ?", String.class, p.id()).stream().findFirst().orElse(null);
        UUID cadreId = calcul.resolution() == null ? null : calcul.resolution().framework().id();
        String cadreVersion = calcul.resolution() == null ? null : calcul.resolution().framework().version();
        jdbc.update("""
                insert into tax_declaration (id, tenant_id, entreprise_id, tax_period_id, tax_type, framework_id, framework_version, status, computed_data, controls, created_at, updated_at)
                values (?, ?, ?, ?, 'TVA', ?, ?, 'DRAFT', ?::jsonb, ?::jsonb, now(), now())
                on conflict (tax_period_id) do update set framework_id = excluded.framework_id, framework_version = excluded.framework_version, computed_data = excluded.computed_data,
                    controls = excluded.controls, updated_at = now(), version = tax_declaration.version + 1
                """, UUID.randomUUID(), p.tenantId(), p.entrepriseId(), p.id(), cadreId, cadreVersion, json(donnees), json(calcul.anomalies()));
        jdbc.update("update tax_period set framework_id = ?, framework_version = ?, updated_at = now() where id = ?", cadreId, cadreVersion, p.id());
        boolean donneesChangees = ancienneEmpreinte != null && !ancienneEmpreinte.equals(empreinte);
        if (donneesChangees) {
            PeriodVersion.bumpOnce(jdbc, p.id());
        }
        if (p.statut() == TaxPeriodStatus.OPEN) {
            if (avancerLeStatut) {
                changerStatut(p.id(), TaxPeriodStatus.PREPARING);
            }
        } else if ((p.statut() == TaxPeriodStatus.READY_FOR_REVIEW || p.statut() == TaxPeriodStatus.REVIEWED) && (donneesChangees || !bloqueursPreparation(calcul).isEmpty())) {
            changerStatut(p.id(), TaxPeriodStatus.PREPARING);                                       // toute modification après la préparation annule la revue
            jdbc.update("update tax_declaration set status = 'DRAFT', reviewed_by = null, reviewed_at = null where tax_period_id = ?", p.id());
        }
        return calcul;
    }

    private void publierControles(Entreprise e, Periode p, List<Anomaly> anomalies) {
        Set<String> presents = new java.util.HashSet<>();
        for (Anomaly a : anomalies) {
            boolean operation = a.ecritureId() != null;
            String type = operation ? "TAX_OPERATION" : "TAX_PERIOD";
            String cle = p.id() + ":" + (operation ? a.ecritureId() : a.key());
            presents.add(a.code() + "#" + type + "#" + cle);
            ControlSeverity gravite = ControlCodes.TAX_FRAMEWORK_NOT_VERIFIED.equals(a.code()) ? ControlSeverity.WARNING : ControlSeverity.ERROR;
            publisher.publier(e, ControlResult.ouvert(a.code(), ControlCategory.TAX, gravite, e.getId(), type, cle, a.message(), "TVA " + p.debut() + " → " + p.fin(), List.of(), Map.of("periode", p.id().toString()),
                    suggestion(a.code())), ControlCodes.typeFinding(a.code()));
        }
        for (Finding f : findingRepository.findActifsParCodes(e.getId(), CODES_FISCAUX)) {
            if (f.getSourceId().startsWith(p.id() + ":") && !presents.contains(f.getControlCode() + "#" + f.getSourceType() + "#" + f.getSourceId())) {
                publisher.resoudre(e.getId(), ControlResult.ouvert(f.getControlCode(), ControlCategory.TAX, ControlSeverity.ERROR, e.getId(), f.getSourceType(), f.getSourceId(), "", "", List.of(), Map.of(), null));
            }
        }
    }

    private void resoudreTout(Entreprise e, UUID periodeId) {
        for (Finding f : findingRepository.findActifsParCodes(e.getId(), CODES_FISCAUX)) {
            if (f.getSourceId().startsWith(periodeId + ":")) {
                publisher.resoudre(e.getId(), ControlResult.ouvert(f.getControlCode(), ControlCategory.TAX, ControlSeverity.ERROR, e.getId(), f.getSourceType(), f.getSourceId(), "", "", List.of(), Map.of(), null));
            }
        }
    }

    private static String suggestion(String code) {
        return switch (code) {
            case "TAX_TREATMENT_MISSING" -> "Décider le traitement fiscal de l'opération (avec justification)";
            case "TAX_RATE_UNRESOLVED" -> "Vérifier le taux appliqué ; décider un traitement ou corriger l'écriture";
            case "VAT_AMOUNT_MISMATCH" -> "Corriger l'écriture, décider un traitement ou ajouter un ajustement justifié";
            case "VAT_BASE_MISMATCH" -> "Comparer la base de TVA à la facture et corriger la source";
            case "DUPLICATE_TAX_DOCUMENT" -> "Vérifier si le document est enregistré deux fois";
            case "TAX_FRAMEWORK_NOT_VERIFIED" -> "Installer et faire vérifier un cadre réglementaire (avec ses sources) couvrant la période ; la période peut être préparée mais pas finalisée";
            default -> "Compléter la période (cadre fiscal vérifié, comptes de TVA configurés, opérations couvertes)";
        };
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ gates

    private List<String> bloqueursPreparation(Calcul c) {
        List<String> l = new ArrayList<>();
        if (c.computation().totals().toReview() > 0) {
            l.add(c.computation().totals().toReview() + " opération(s) à revoir");
        }
        long autres = c.bloquantsPreparation().stream().filter(a -> !ControlCodes.TAX_TREATMENT_MISSING.equals(a.code()) && !ControlCodes.TAX_RATE_UNRESOLVED.equals(a.code())).count();
        if (autres > 0) {
            l.add(autres + " anomalie(s) à corriger");
        }
        return l;
    }

    private List<String> bloqueursFinalisation(Calcul c, boolean avecRevue) {
        List<String> l = new ArrayList<>(bloqueursPreparation(c));
        if (c.resolution() == null || !c.resolution().verifie()) {
            l.add("aucun cadre fiscal vérifié");
        } else if (!c.computation().totals().fiscalComplete()) {
            l.add("montants fiscaux incomplets");
        }
        if (c.computation().totals().unexplained().signum() != 0) {
            l.add("écart TVA comptable / fiscale non expliqué");
        }
        if (reglement.creditOuvertureAValider(c.periode().id())) {
            l.add("crédit d'ouverture à valider (aucun cadre vérifié ne prévoit le report)");
        }
        return l;
    }

    /** Codes machine des raisons qui empêchent la finalisation (le frontend les explique ; il ne les calcule pas). */
    private List<String> raisonsBlocage(Calcul c) {
        List<String> r = new ArrayList<>();
        if (c.resolution() == null || !c.resolution().verifie()) {
            r.add("REGULATORY_PACK_NOT_VERIFIED");
        } else if (!c.computation().totals().fiscalComplete()) {
            r.add("FISCAL_AMOUNTS_INCOMPLETE");
        }
        if (c.computation().totals().toReview() > 0) {
            r.add("OPERATIONS_TO_REVIEW");
        }
        if (c.bloquantsPreparation().stream().anyMatch(a -> !ControlCodes.TAX_TREATMENT_MISSING.equals(a.code()) && !ControlCodes.TAX_RATE_UNRESOLVED.equals(a.code()))) {
            r.add("CONTROLS_OPEN");
        }
        if (c.computation().totals().unexplained().signum() != 0) {
            r.add("UNEXPLAINED_GAP");
        }
        if (reglement.creditOuvertureAValider(c.periode().id())) {
            r.add("OPENING_CREDIT_TO_REVIEW");
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ outils

    private Periode periode(UUID id, UUID entrepriseId) {
        List<Periode> l = jdbc.query("select id, tenant_id, entreprise_id, period_start, period_end, status, framework_id, framework_version, version from tax_period where id = ? and entreprise_id = ?", (rs, i) -> new Periode(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("entreprise_id", UUID.class), rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                TaxPeriodStatus.valueOf(rs.getString("status")), rs.getObject("framework_id", UUID.class), rs.getString("framework_version"), rs.getLong("version")), id, entrepriseId);
        return l.stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Période fiscale introuvable"));
    }

    /**
     * Verrou de ligne + contrôle de version (optimistic locking) : si {@code expectedVersion} est fournie et ne correspond plus à la version courante, la modification est refusée (409) au lieu
     * d'écraser silencieusement le travail d'un autre utilisateur. {@code mutation} : la version est incrémentée (annulée avec la transaction si la mutation échoue).
     */
    private Periode verrouiller(UUID id, UUID entrepriseId, Long expectedVersion, boolean mutation) {
        jdbc.queryForList("select id from tax_period where id = ? and entreprise_id = ? for update", id, entrepriseId);
        Periode p = periode(id, entrepriseId);
        if (expectedVersion != null && expectedVersion != p.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette période a été modifiée par un autre utilisateur (version courante " + p.version() + ", version fournie " + expectedVersion
                    + "). Rechargez les dernières données avant de poursuivre.");
        }
        if (mutation) {
            PeriodVersion.bumpOnce(jdbc, id);
        }
        return p;
    }

    private void changerStatut(UUID id, TaxPeriodStatus statut) {
        jdbc.update("update tax_period set status = ?, updated_at = now() where id = ?", statut.name(), id);
    }

    private void auditerStatut(UtilisateurPrincipal principal, Periode avant, TaxPeriodStatus apres) {
        audit.enregistrerActionHumaine(avant.tenantId(), principal.getId(), "PERIODE_FISCALE_STATUT_MODIFIE", "PERIODE_FISCALE", avant.id().toString(), Map.of("avant", avant.statut().name(), "apres", apres.name()));
    }

    private static void exigerModifiable(Periode p) {
        if (p.statut() == TaxPeriodStatus.FINAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette période est finalisée : la déclaration est immuable");
        }
    }

    private static void exigerPreparation(UtilisateurPrincipal p) {
        if (!DossierRoles.peutPreparer(p)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Réservé aux collaborateurs et experts-comptables du cabinet");
        }
    }

    private static void exigerRevue(UtilisateurPrincipal p) {
        if (!DossierRoles.peutRevoir(p)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La revue d'une déclaration fiscale est réservée à l'expert-comptable");
        }
    }

    private static void exigerFinalisation(UtilisateurPrincipal p) {
        if (!DossierRoles.peutFinaliser(p)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La finalisation d'une déclaration fiscale est réservée à l'expert-comptable");
        }
    }

    private static FrameworkInfo info(Resolution r) {
        if (r == null) {
            return null;
        }
        TaxFramework f = r.framework();
        return new FrameworkInfo(f.id(), f.jurisdiction(), f.version(), f.status(), r.verifie(), f.definitionHash(), f.sourceReferences(), f.effectiveFrom(), f.effectiveTo());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadFinal(UUID periodeId) {
        String payload = jdbc.queryForObject("select payload from tax_declaration_snapshot where tax_period_id = ?", String.class, periodeId);
        try {
            return MAPPER.readValue(payload, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Snapshot fiscal illisible", e);
        }
    }

    /** Un dossier FINAL se relit depuis son snapshot (jamais recalculé). */
    private Computation lireCalculFinal(Map<String, Object> payload) {
        try {
            var totals = MAPPER.convertValue(payload.get("totals"), VatModel.Totals.class);
            var reconciliation = MAPPER.convertValue(payload.get("reconciliation"), VatModel.Reconciliation.class);
            List<Line> lines = MAPPER.convertValue(payload.get("lines"), MAPPER.getTypeFactory().constructCollectionType(List.class, Line.class));
            List<VatModel.DeclarationLine> declaration = MAPPER.convertValue(payload.get("declaration"), MAPPER.getTypeFactory().constructCollectionType(List.class, VatModel.DeclarationLine.class));
            return new Computation(lines, totals, reconciliation, List.of(), declaration, String.valueOf(payload.get("basis")), Boolean.TRUE.equals(payload.get("officialStructure")));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Snapshot fiscal illisible : " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static FrameworkInfo cadreFinal(Map<String, Object> payload) {
        Map<String, Object> f = (Map<String, Object>) payload.get("framework");
        return new FrameworkInfo(UUID.fromString((String) f.get("id")), (String) f.get("jurisdiction"), (String) f.get("version"), (String) f.get("status"), true, (String) f.get("definitionHash"),
                (List<String>) f.get("sourceReferences"), LocalDate.parse((String) f.get("effectiveFrom")), null);
    }

    private static String empreinte(Calcul c) {
        return sha256(json(c.computation().totals()) + json(c.computation().declaration()) + c.computation().lines().stream().map(l -> l.ecritureId() + ":" + l.treatment() + ":" + l.vatAccounted() + ":" + l.vatFiscal()).collect(Collectors.joining(",")));
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime horodatage(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime odt(Object o) {
        return o instanceof java.sql.Timestamp ts ? ts.toInstant().atOffset(ZoneOffset.UTC) : o instanceof OffsetDateTime d ? d : null;
    }
}
