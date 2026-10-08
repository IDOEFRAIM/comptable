package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.comptabilite.balance.AccountBalance;
import com.example.comptable.comptabilite.balance.AccountingBalanceEngine;
import com.example.comptable.comptabilite.balance.ExerciseBalances;
import com.example.comptable.comptabilite.balance.ReportingView;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.CalculationType;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.FormulaEvaluator;
import com.example.comptable.etatsfinanciers.definition.MappingRole;
import com.example.comptable.etatsfinanciers.definition.StatementLineDefinition;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.AccountInput;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.MappedAccount;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService.Position;
import com.example.comptable.etatsfinanciers.service.CashFlowExclusionClassifier.Classe;
import com.example.comptable.etatsfinanciers.service.CashFlowExclusionClassifier.Classification;
import com.example.comptable.etatsfinanciers.service.CashFlowExclusionClassifier.TermeCafg;
import com.example.comptable.etatsfinanciers.service.StatementResult.Line;
import com.example.comptable.etatsfinanciers.service.StatementResult.LineAccount;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tableau des flux de tresorerie (phase F7), par la methode officielle (reconstitution a partir des soldes de bilan N et N-1, du compte de resultat et des mouvements).
 *
 * <p>Ce qui est calcule : ZA = BT - DT a l'ouverture, ZH = BT - DT a la cloture, FA (CAFG) par ses termes verifies, FB/FD/FE par variation des comptes classes
 * {@code OPERATING} par {@link CashFlowExclusionClassifier} (jamais de liste d'exclusion inventee : un compte non classe qui varie rend la ligne INCOMPLETE), FC = -(BB N - BB N-1) sur
 * stocks nets, FF/FG/FH/FI/FJ par la relation de la source dans le seul cas ou ses autres termes sont PROUVES nuls (aucune cession, reevaluation, avance, acquisition sans
 * flux possible), et FK a FQ uniquement s'il n'existe aucun mouvement sur leurs comptes (la nature numeraire/non numeraire d'un evenement n'est pas deduite du numero de compte).
 * Toute autre situation est {@code INCOMPLETE} avec la raison, jamais un zero par defaut. Les variations non monetaires ne sont jamais converties en flux.</p>
 *
 * <p>La CAFG n'est calculee que si aucun compte des cinq termes {@code PARTIAL} (revenus financiers, gains et pertes de change, frais financiers, produits HAO) ne porte de mouvement.
 * ZA + ZG = ZH est controle ({@code CASH_FLOW_MISMATCH}) uniquement quand le TFT est autrement complet. La colonne N-1 du TFT n'est pas calculee.</p>
 */
@Service
@RequiredArgsConstructor
public class CashFlowStatementService {

    private static final Pattern PLAGE = Pattern.compile("SUM\\(([A-Z]{2})\\.\\.([A-Z]{2})\\)");

    private final EntrepriseAccessService entrepriseAccessService;
    private final FinancialStatementDefinitionRegistry registry;
    private final AccountingBalanceEngine balanceEngine;
    private final StatementComputationCache cache;
    private final FinancialStatementMappingResolver resolver;
    private final BalanceSheetService balanceSheetService;
    private final IncomeStatementService incomeStatementService;
    private final CashFlowExclusionClassifier classifier;
    private final CashFlowEventResolver eventResolver;

    /** Resultat du TFT : l'etat (statut READY ou INCOMPLETE) et ses controles. */
    @Transactional(readOnly = true)
    public StatementResult generer(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return calculer(entrepriseId, exerciceId);
    }

    private record Flux(BigDecimal montant, List<String> raisons, List<LineAccount> comptes) {
        static Flux ok(BigDecimal montant, List<LineAccount> comptes) {
            return new Flux(montant, List.of(), comptes);
        }

        static Flux incomplet(List<String> raisons) {
            return new Flux(null, List.copyOf(raisons), List.of());
        }
    }

    StatementResult calculer(UUID entrepriseId, UUID exerciceId) {
        return cache.enCache(java.util.List.of("TFT", entrepriseId, exerciceId), () -> calculerSansCache(entrepriseId, exerciceId));
    }

    private StatementResult calculerSansCache(UUID entrepriseId, UUID exerciceId) {
        FinancialStatementDefinition defTft = registry.courante(StatementType.TFT);
        FinancialStatementDefinition defActif = registry.courante(StatementType.BILAN_ACTIF);
        FinancialStatementDefinition defPassif = registry.courante(StatementType.BILAN_PASSIF);
        FinancialStatementDefinition defCr = registry.courante(StatementType.COMPTE_RESULTAT);

        ExerciseBalances legal = cache.soldes(entrepriseId, exerciceId, ReportingView.LEGAL_LEDGER);
        ExerciseBalances activite = cache.soldes(entrepriseId, exerciceId, ReportingView.PERIOD_ACTIVITY);
        ExerciceComptable exercice = legal.exercice();

        Position ouverture = balanceSheetService.position(defActif, defPassif, legal.current(), Map.of(), true);
        Position cloture = balanceSheetService.position(defActif, defPassif, legal.current(), BalanceSheetService.mouvementsDeCloture(legal.current(), activite.current()), false);
        StatementResult cr = incomeStatementService.calculer(entrepriseId, exerciceId);

        List<String> raisonsGlobales = new ArrayList<>();
        if (!cloture.nonMappes().isEmpty() || !cloture.ambigus().isEmpty() || !ouverture.nonMappes().isEmpty() || !ouverture.ambigus().isEmpty()) {
            raisonsGlobales.add("Bilan incomplet (comptes non mappes ou ambigus) : la tresorerie et les variations ne sont pas fiables");
        }

        List<AccountInput> inclassesPourFinding = new ArrayList<>();
        Map<String, Flux> flux = new LinkedHashMap<>();
        Map<String, List<String>> cafgParTerme = new LinkedHashMap<>();

        // ---- tresorerie : ZA et ZH sur les soldes des exercices reels (ouverture = report des exercices precedents, jamais « date - 1 an »)
        BigDecimal za = raisonsGlobales.isEmpty() ? tresorerieNette(ouverture) : null;
        BigDecimal zh = raisonsGlobales.isEmpty() ? tresorerieNette(cloture) : null;

        // ---- FA : CAFG
        flux.put("FA", cafg(defCr, cr, activite.current(), inclassesPourFinding, cafgParTerme));

        // ---- FB, FC, FD, FE
        flux.put("FB", variation("FB", Set.of("BA"), true, ouverture, cloture, true, inclassesPourFinding));
        flux.put("FC", variationStocks(ouverture, cloture));
        flux.put("FD", variation("FD", Set.of("BH", "BI", "BJ"), true, ouverture, cloture, true, inclassesPourFinding));
        flux.put("FE", variation("FE", Set.of("DH", "DI", "DJ", "DK", "DM"), false, ouverture, cloture, false, inclassesPourFinding));

        // ---- investissement et financement : par les ecritures et leurs evenements de tresorerie
        CashFlowEventResolver.Resultat evenements = eventResolver.resoudre(entrepriseId, exercice.getDateDebut(), exercice.getDateFin(), defActif, defPassif);
        for (String code : CashFlowEventResolver.LIGNES_INVESTISSEMENT) {
            flux.put(code, fluxEvenement(code, evenements));
        }
        for (String code : CashFlowEventResolver.LIGNES_FINANCEMENT) {
            flux.put(code, fluxEvenement(code, evenements));
        }

        // ---- assemblage
        Map<String, BigDecimal> alimentees = new HashMap<>();
        flux.forEach((code, f) -> alimentees.put(code, f.montant()));
        alimentees.put("ZA", za);
        alimentees.put("ZH", zh);
        Map<String, BigDecimal> valeurs = FormulaEvaluator.evaluerDefinition(defTft, alimentees);

        List<Line> lignes = new ArrayList<>();
        Set<String> raisons = new LinkedHashSet<>(raisonsGlobales);
        Map<String, List<String>> raisonsParLigne = new HashMap<>();
        for (StatementLineDefinition l : defTft.lines().stream().sorted(Comparator.comparingInt(StatementLineDefinition::order)).toList()) {
            List<String> r = new ArrayList<>();
            if (l.calculationType() == CalculationType.FORMULA) {
                for (String op : FormulaEvaluator.codesReferences(l.formula())) {
                    for (String x : raisonsParLigne.getOrDefault(op, List.of())) {
                        String reprise = op + " : " + (x.contains(" : ") ? x.substring(x.indexOf(" : ") + 3) : x);
                        if (!r.contains(reprise)) {
                            r.add(reprise);
                        }
                    }
                }
                if (r.isEmpty() && valeurs.get(l.code()) == null) {
                    r.add("operande(s) non calculable(s)");
                }
            } else if (flux.containsKey(l.code())) {
                flux.get(l.code()).raisons().forEach(x -> r.add(l.code() + " : " + x));
            } else if (valeurs.get(l.code()) == null) {
                r.add(l.code() + " : tresorerie non calculable (Bilan incomplet)");
            }
            raisonsParLigne.put(l.code(), r);
            lignes.add(new Line(l.code(), l.label(), l.order(), l.calculationType(), l.verificationStatus(), l.provenanceType(), valeurs.get(l.code()), null, null, null,
                    r.isEmpty() ? l.calculationBlockedReason() : String.join(" ; ", r), flux.containsKey(l.code()) ? flux.get(l.code()).comptes() : List.of(), r.isEmpty(), r));
        }
        for (StatementLineDefinition l : defTft.lines()) {
            if (l.calculationType() != CalculationType.FORMULA) {
                raisons.addAll(raisonsParLigne.get(l.code()));
            }
        }

        List<ControlResult> controles = new ArrayList<>();
        if (!inclassesPourFinding.isEmpty()) {
            controles.add(StatementControls.comptesNonMappes(entrepriseId, exerciceId, StatementType.TFT, distinct(inclassesPourFinding),
                    "TFT : compte dont la variation n'est classee ni exploitation ni exclusion par la source"));
        }
        if (!cafgParTerme.isEmpty()) {
            controles.add(StatementControls.composantCafgNonResolu(entrepriseId, exerciceId, cafgParTerme));
        }
        if (!evenements.ecrituresNonQualifiees().isEmpty()) {
            List<String> pieces = evenements.ecrituresNonQualifiees().values().stream().flatMap(List::stream).distinct().sorted().toList();
            controles.add(StatementControls.evenementTresorerieNonQualifie(entrepriseId, exerciceId, pieces, List.copyOf(evenements.ecrituresNonQualifiees().keySet())));
        }
        BigDecimal zg = valeurs.get("ZG");
        if (raisonsGlobales.isEmpty() && zg != null && valeurs.get("ZA") != null && valeurs.get("ZH") != null
                && valeurs.get("ZA").add(zg).compareTo(valeurs.get("ZH")) != 0) {
            controles.add(StatementControls.ecartDeFluxDeTresorerie(entrepriseId, exerciceId, "ZA + ZG = ZH", valeurs.get("ZH"), valeurs.get("ZA").add(zg)));
            raisons.add("ZA + ZG (" + valeurs.get("ZA").add(zg).toPlainString() + ") different de ZH (" + valeurs.get("ZH").toPlainString() + ")");
        }
        boolean complet = raisons.isEmpty() && lignes.stream().allMatch(Line::complete);
        var dataset = cache.empreinte(entrepriseId, exerciceId, exercice.getDateDebut(), exercice.getDateFin(), ReportingView.LEGAL_LEDGER);
        return new StatementResult(StatementType.TFT, defTft.framework(), defTft.version(), defTft.contentHash(), exerciceId, exercice.getDateDebut(), exercice.getDateFin(),
                null, null, null, complet ? Status.READY : Status.INCOMPLETE, List.copyOf(raisons), lignes, List.of(), List.of(), controles, dataset, OffsetDateTime.now());
    }

    private static List<AccountInput> distinct(List<AccountInput> comptes) {
        Map<UUID, AccountInput> parId = new LinkedHashMap<>();
        comptes.forEach(c -> parId.putIfAbsent(c.id(), c));
        return List.copyOf(parId.values());
    }

    private static BigDecimal tresorerieNette(Position p) {
        return p.net("BT").subtract(p.passif("DT"));
    }

    // ------------------------------------------------------------------------------------------------ CAFG

    private Flux cafg(FinancialStatementDefinition defCr, StatementResult cr, List<AccountBalance> activite, List<AccountInput> inclasses, Map<String, List<String>> cafgParTerme) {
        List<String> raisons = new ArrayList<>();
        BigDecimal ebe = cr.lines().stream().filter(l -> l.code().equals("XD")).findFirst().map(Line::amountN).orElse(null);
        if (cr.status() != Status.READY || ebe == null) {
            raisons.add("EBE (XD) non disponible : compte de resultat " + cr.status());
        }
        List<AccountInput> comptes = activite.stream().filter(s -> s.periodNet().signum() != 0)
                .map(s -> new AccountInput(s.accountId(), s.accountNumber(), s.accountLabel(), s.periodNet())).toList();
        Set<String> alimenteEbe = lignesAlimentant(defCr, "XD");
        Map<UUID, String> ligneCr = new HashMap<>();
        resolver.resoudre(defCr, comptes).mapped().forEach(m -> ligneCr.put(m.account().id(), m.lineCode()));

        BigDecimal total = ebe;
        List<LineAccount> detail = new ArrayList<>();
        Set<String> partiels = new TreeSet<>();
        for (AccountInput c : comptes) {
            if (!StatementAccounts.estCompteDeGestion(c.number())) {
                continue;
            }
            String ligne = ligneCr.get(c.id());
            if (ligne != null && alimenteEbe.contains(ligne) && classifier.termeCafg(c.number()) != TermeCafg.VNC_CESSION && classifier.termeCafg(c.number()) != TermeCafg.PRODUIT_CESSION) {
                continue;   // deja dans l'EBE
            }
            TermeCafg terme = classifier.termeCafg(c.number());
            BigDecimal n = c.net();   // debit - credit
            switch (terme) {
                case VNC_CESSION, PRODUIT_CESSION -> {
                    total = total == null ? null : total.add(n);                      // +654 ; -754 (net negatif)
                    detail.add(new LineAccount(c.id(), c.number(), c.label(), MappingRole.GROSS, n, null));
                }
                case TRANSFERT_FINANCIER, TRANSFERT_HAO, PARTICIPATION, IMPOT -> {
                    total = total == null ? null : total.subtract(n);                 // + transferts (credit) ; - participation et impot (debit)
                    detail.add(new LineAccount(c.id(), c.number(), c.label(), MappingRole.GROSS, n.negate(), null));
                }
                case CESSION_HAO, NON_CASH -> { /* hors CAFG */ }
                case PARTIEL -> {
                    partiels.add(c.number());
                    cafgParTerme.computeIfAbsent("terme PARTIAL (revenus/frais financiers, gains/pertes de change, produits HAO)", k -> new ArrayList<>()).add(c.number());
                }
                case INCLASSE -> {
                    inclasses.add(c);
                    raisons.add("compte " + c.number() + " (classe de gestion) non classe dans la CAFG");
                    cafgParTerme.computeIfAbsent("compte de gestion non classe", k -> new ArrayList<>()).add(c.number());
                }
            }
        }
        if (!partiels.isEmpty()) {
            raisons.add("UNRESOLVED_CAFG_COMPONENT : mouvements sur des comptes de termes PARTIAL de la CAFG (revenus/frais financiers, gains/pertes de change, produits HAO) : comptes "
                    + String.join(", ", partiels));
        }
        return raisons.isEmpty() ? Flux.ok(total, detail) : Flux.incomplet(raisons);
    }

    /** Lignes MAPPED dont le montant alimente (transitivement) une ligne de formule. */
    private static Set<String> lignesAlimentant(FinancialStatementDefinition def, String code) {
        Map<String, StatementLineDefinition> parCode = new HashMap<>();
        def.lines().forEach(l -> parCode.put(l.code(), l));
        Set<String> feuilles = new HashSet<>();
        collecter(def, parCode, code, feuilles, new HashSet<>());
        return feuilles;
    }

    private static void collecter(FinancialStatementDefinition def, Map<String, StatementLineDefinition> parCode, String code, Set<String> feuilles, Set<String> vus) {
        if (!vus.add(code)) {
            return;
        }
        StatementLineDefinition l = parCode.get(code);
        if (l.calculationType() != CalculationType.FORMULA) {
            feuilles.add(code);
            return;
        }
        Set<String> refs = new LinkedHashSet<>(FormulaEvaluator.codesReferences(l.formula()));
        Matcher m = PLAGE.matcher(l.formula());
        while (m.find()) {
            int debut = parCode.get(m.group(1)).order();
            int fin = parCode.get(m.group(2)).order();
            def.lines().stream().filter(x -> x.order() >= debut && x.order() <= fin).forEach(x -> refs.add(x.code()));
        }
        refs.forEach(r -> collecter(def, parCode, r, feuilles, vus));
    }

    // ------------------------------------------------------------------------------------------------ FB, FD, FE

    /**
     * Variation des comptes d'une rubrique de bilan, par compte (jamais par somme de rubrique) afin d'appliquer la classification. Les comptes {@code OPERATING} entrent ; les exclus
     * (investissement, financement, non monetaire, conversion) sont ecartes ; un compte non classe qui varie rend la ligne incomplete.
     *
     * @param actif       rubriques de l'Actif (valeur = solde debiteur net) ou du Passif (valeur = solde crediteur)
     * @param negatif     {@code true} : flux = -variation (emplois) ; {@code false} : flux = +variation (ressources)
     */
    private Flux variation(String ligne, Set<String> rubriques, boolean actif, Position ouverture, Position cloture, boolean negatif, List<AccountInput> inclasses) {
        Map<UUID, BigDecimal> avant = contributions(actif ? ouverture.mappesActif() : ouverture.mappesPassif(), rubriques, actif);
        Map<UUID, BigDecimal> apres = contributions(actif ? cloture.mappesActif() : cloture.mappesPassif(), rubriques, actif);
        Map<UUID, MappedAccount> comptes = new HashMap<>();
        (actif ? ouverture.mappesActif() : ouverture.mappesPassif()).forEach(m -> comptes.put(m.account().id(), m));
        (actif ? cloture.mappesActif() : cloture.mappesPassif()).forEach(m -> comptes.put(m.account().id(), m));

        BigDecimal somme = BigDecimal.ZERO;
        List<LineAccount> detail = new ArrayList<>();
        List<String> raisons = new ArrayList<>();
        Set<UUID> ids = new LinkedHashSet<>(avant.keySet());
        ids.addAll(apres.keySet());
        for (UUID id : ids) {
            BigDecimal delta = apres.getOrDefault(id, BigDecimal.ZERO).subtract(avant.getOrDefault(id, BigDecimal.ZERO));
            if (delta.signum() == 0) {
                continue;
            }
            MappedAccount m = comptes.get(id);
            Classification c = classifier.classer(m.account().number(), m.role());
            if (c.classe() == Classe.OPERATING) {
                somme = somme.add(delta);
                detail.add(new LineAccount(id, m.account().number(), m.account().label(), m.role(), negatif ? delta.negate() : delta, null));
            } else if (c.classe() == Classe.UNCLASSIFIED) {
                raisons.add("la variation du compte " + m.account().number() + " n'est classee ni exploitation ni exclusion (aucune regle de la source)");
                inclasses.add(m.account());
            }
        }
        if (!raisons.isEmpty()) {
            return Flux.incomplet(raisons);
        }
        return Flux.ok(negatif ? somme.negate() : somme, detail);
    }

    private Map<UUID, BigDecimal> contributions(List<MappedAccount> mappes, Set<String> rubriques, boolean actif) {
        Map<UUID, BigDecimal> resultat = new HashMap<>();
        for (MappedAccount m : mappes) {
            if (!rubriques.contains(m.lineCode())) {
                continue;
            }
            // Actif : solde debiteur net (une depreciation, au credit, vient en deduction) ; Passif : solde crediteur
            resultat.merge(m.account().id(), actif ? m.account().net() : m.account().net().negate(), BigDecimal::add);
        }
        return resultat;
    }

    /**
     * FC = -(variation des stocks et en-cours) calculee sur les comptes de stock BRUTS : la variation des depreciations de stocks (comptes de depreciation de BB) n'est pas un
     * flux de tresorerie et n'y entre jamais. Un compte de stock mappe sur la ligne mais de role depreciation est ignore ; le brut est la variation economique.
     */
    private Flux variationStocks(Position ouverture, Position cloture) {
        Map<UUID, BigDecimal> avant = new HashMap<>();
        Map<UUID, BigDecimal> apres = new HashMap<>();
        Map<UUID, MappedAccount> comptes = new HashMap<>();
        ouverture.mappesActif().stream().filter(m -> m.lineCode().equals("BB") && m.role() == MappingRole.GROSS).forEach(m -> {
            avant.put(m.account().id(), m.account().net());
            comptes.put(m.account().id(), m);
        });
        cloture.mappesActif().stream().filter(m -> m.lineCode().equals("BB") && m.role() == MappingRole.GROSS).forEach(m -> {
            apres.put(m.account().id(), m.account().net());
            comptes.put(m.account().id(), m);
        });
        BigDecimal total = BigDecimal.ZERO;
        List<LineAccount> detail = new ArrayList<>();
        for (UUID id : comptes.keySet()) {
            BigDecimal delta = apres.getOrDefault(id, BigDecimal.ZERO).subtract(avant.getOrDefault(id, BigDecimal.ZERO));
            if (delta.signum() != 0) {
                total = total.add(delta);
                MappedAccount m = comptes.get(id);
                detail.add(new LineAccount(id, m.account().number(), m.account().label(), m.role(), delta.negate(), null));
            }
        }
        return Flux.ok(total.negate(), detail);
    }

    private Flux fluxEvenement(String code, CashFlowEventResolver.Resultat evenements) {
        if (!evenements.complet(code)) {
            return Flux.incomplet(evenements.raisons().get(code));
        }
        List<LineAccount> composants = new ArrayList<>();
        return Flux.ok(evenements.montants().get(code), composants);
    }

    /** Ecritures de tresorerie dont l'evenement n'est pas qualifie ou est incoherent (avec leurs lignes) : travail par exception du comptable. */
    @Transactional(readOnly = true)
    public List<CashFlowEventResolver.EcritureNonQualifiee> ecrituresNonQualifiees(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = cache.soldes(entrepriseId, exerciceId, ReportingView.LEGAL_LEDGER).exercice();
        return eventResolver.resoudre(entrepriseId, exercice.getDateDebut(), exercice.getDateFin(), registry.courante(StatementType.BILAN_ACTIF),
                registry.courante(StatementType.BILAN_PASSIF)).details();
    }

    /** Composants d'une ligne du TFT : ecritures et evenements pour FF a FQ (drill-down). */
    @Transactional(readOnly = true)
    public List<CashFlowEventResolver.Composant> composants(UUID entrepriseId, UUID exerciceId, String ligne, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = cache.soldes(entrepriseId, exerciceId, ReportingView.LEGAL_LEDGER).exercice();
        var resultat = eventResolver.resoudre(entrepriseId, exercice.getDateDebut(), exercice.getDateFin(), registry.courante(StatementType.BILAN_ACTIF),
                registry.courante(StatementType.BILAN_PASSIF));
        return resultat.composants().stream().filter(c -> c.ligneTft().equals(ligne)).toList();
    }

    // ------------------------------------------------------------------------------------------------ formules brutes

    /**
     * Applique les formules officielles du TFT.
     *
     * @param flux            valeurs des lignes alimentees par la methode (FA a FQ), par code ; une ligne absente rend ses totaux inconnus (jamais zero par defaut)
     * @param tresorerieNetteOuverture tresorerie-actif N-1 - tresorerie-passif N-1 (ZA)
     * @param tresorerieNetteCloture   tresorerie-actif N - tresorerie-passif N (ZH)
     */
    public Map<String, BigDecimal> evaluer(Map<String, BigDecimal> flux, BigDecimal tresorerieNetteOuverture, BigDecimal tresorerieNetteCloture) {
        FinancialStatementDefinition definition = registry.courante(StatementType.TFT);
        Map<String, BigDecimal> alimentees = new HashMap<>(flux);
        alimentees.put("ZA", tresorerieNetteOuverture);
        alimentees.put("ZH", tresorerieNetteCloture);
        return FormulaEvaluator.evaluerDefinition(definition, alimentees);
    }

    /** Invariants officiels : ZA + ZG = ZH. Un constat {@code CASH_FLOW_MISMATCH} par invariant viole ; rien si une valeur est inconnue. */
    public List<ControlResult> controler(UUID entrepriseId, UUID exerciceId, Map<String, BigDecimal> valeurs) {
        List<ControlResult> constats = new ArrayList<>();
        BigDecimal za = valeurs.get("ZA");
        BigDecimal zg = valeurs.get("ZG");
        BigDecimal zh = valeurs.get("ZH");
        if (za != null && zg != null && zh != null && za.add(zg).compareTo(zh) != 0) {
            constats.add(StatementControls.ecartDeFluxDeTresorerie(entrepriseId, exerciceId, "ZA + ZG = ZH", zh, za.add(zg)));
        }
        return constats;
    }
}
