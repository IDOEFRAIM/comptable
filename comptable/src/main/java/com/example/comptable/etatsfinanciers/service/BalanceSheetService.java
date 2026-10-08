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
import com.example.comptable.etatsfinanciers.definition.SignConvention;
import com.example.comptable.etatsfinanciers.definition.StatementLineDefinition;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.definition.VerificationStatus;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.AccountInput;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.AmbiguousAccount;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.MappedAccount;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.MappingOutcome;
import com.example.comptable.etatsfinanciers.service.StatementResult.AccountRef;
import com.example.comptable.etatsfinanciers.service.StatementResult.Ambiguous;
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
import java.util.UUID;

/**
 * Bilan SYSCOHADA (Systeme normal), phase F5. Pipeline unique : {@link AccountingBalanceEngine} (vue {@code LEGAL_LEDGER}) -&gt; {@link FinancialStatementMappingResolver} applique aux DEUX
 * definitions (Actif et Passif) -&gt; formules des definitions -&gt; controles. Aucun prefixe de compte dans ce service : les regles sont celles des definitions versionnees.
 *
 * <p>Un compte de classe 1 a 5 (hors classe 13, portee par la ligne CJ) que ni l'Actif ni le Passif ne classent est {@code UNMAPPED} (jamais range par defaut) ; un compte classe par
 * les deux est {@code AMBIGUOUS} (exclu). Le Bilan est {@code READY} uniquement si : aucun compte non mappe ou ambigu, aucune ligne partiellement codee portant un solde, resultat du
 * bilan (CJ) egal au resultat du compte de resultat (XI), et {@code BZ = DZ} a zero pres (BigDecimal). Sinon {@code INCOMPLETE} : jamais de FINAL partiel.</p>
 *
 * <p>CJ = -(solde net des comptes de classe 13 + solde net des comptes de classes 6, 7 et 8), vue legale (identite comptable, regle {@code DERIVED_FROM_PRIMARY} de la matrice).</p>
 */
@Service
@RequiredArgsConstructor
public class BalanceSheetService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final FinancialStatementDefinitionRegistry registry;
    private final AccountingBalanceEngine balanceEngine;
    private final StatementComputationCache cache;
    private final FinancialStatementMappingResolver resolver;
    private final IncomeStatementService incomeStatementService;

    /** Resultat du Bilan : les deux etats portent le meme statut (un Bilan ne se finalise qu'entier). */
    public record BilanResult(StatementResult actif, StatementResult passif, Status status, List<String> reasons, List<ControlResult> controls,
                              BigDecimal totalActifN, BigDecimal totalPassifN, BigDecimal resultatBilanN, BigDecimal resultatCompteResultatN) {
    }

    @Transactional(readOnly = true)
    public BilanResult bilan(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return calculer(entrepriseId, exerciceId);
    }

    @Transactional(readOnly = true)
    public StatementResult actif(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        return bilan(entrepriseId, exerciceId, principal).actif();
    }

    @Transactional(readOnly = true)
    public StatementResult passif(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        return bilan(entrepriseId, exerciceId, principal).passif();
    }

    // ------------------------------------------------------------------------------------------------ calcul

    /** Position du bilan a une date : montants par ligne, comptes mappes, non mappes, ambigus. */
    record Position(Map<String, BigDecimal> brut, Map<String, BigDecimal> amort, Map<String, BigDecimal> net, Map<String, BigDecimal> passif, BigDecimal resultat,
                    List<MappedAccount> mappesActif, List<MappedAccount> mappesPassif, List<AccountInput> nonMappes, List<AmbiguousAccount> ambigus,
                    Map<String, List<String>> raisonsActif, Map<String, List<String>> raisonsPassif) {

        BigDecimal net(String code) {
            return net.getOrDefault(code, BigDecimal.ZERO);
        }

        BigDecimal passif(String code) {
            return passif.getOrDefault(code, BigDecimal.ZERO);
        }
    }

    BilanResult calculer(UUID entrepriseId, UUID exerciceId) {
        return cache.enCache(java.util.List.of("BILAN", entrepriseId, exerciceId), () -> calculerSansCache(entrepriseId, exerciceId));
    }

    private BilanResult calculerSansCache(UUID entrepriseId, UUID exerciceId) {
        FinancialStatementDefinition defActif = registry.courante(StatementType.BILAN_ACTIF);
        FinancialStatementDefinition defPassif = registry.courante(StatementType.BILAN_PASSIF);
        ExerciseBalances soldes = cache.soldes(entrepriseId, exerciceId, ReportingView.LEGAL_LEDGER);
        ExerciceComptable exercice = soldes.exercice();

        ExerciseBalances activite = cache.soldes(entrepriseId, exerciceId, ReportingView.PERIOD_ACTIVITY);
        Position n = position(defActif, defPassif, soldes.current(), mouvementsDeCloture(soldes.current(), activite.current()), false);
        Position n1 = soldes.previousExercice().isPresent()
                ? position(defActif, defPassif, soldes.previous(), mouvementsDeCloture(soldes.previous(), activite.previous()), false) : null;

        StatementResult cr = incomeStatementService.calculer(entrepriseId, exerciceId);
        BigDecimal xi = cr.lines().stream().filter(l -> l.code().equals("XI")).findFirst().map(Line::amountN).orElse(null);

        List<String> raisons = new ArrayList<>();
        List<ControlResult> controles = new ArrayList<>();
        if (!n.nonMappes().isEmpty()) {
            raisons.add(n.nonMappes().size() + " compte(s) de bilan non mappe(s) : etat incomplet");
            controles.add(StatementControls.comptesNonMappes(entrepriseId, exerciceId, StatementType.BILAN_ACTIF, n.nonMappes(),
                    "bilan : comptes de classes 1 a 5 sans regle verifiee dans l'Actif ni dans le Passif"));
        }
        if (!n.ambigus().isEmpty()) {
            raisons.add(n.ambigus().size() + " compte(s) a correspondance ambigue");
            controles.add(StatementControls.correspondanceAmbigue(entrepriseId, exerciceId, StatementType.BILAN_ACTIF, n.ambigus()));
        }
        for (var e : n.raisonsActif().entrySet()) {
            if (!e.getValue().isEmpty() && n.net(e.getKey()).signum() != 0 && ligne(defActif, e.getKey()).calculationType() != CalculationType.FORMULA) {
                raisons.add("Actif " + e.getKey() + " : " + String.join(" ; ", e.getValue()));
            }
        }
        if (xi != null && n.resultat().compareTo(xi) != 0) {
            raisons.add("resultat du bilan (CJ " + n.resultat().toPlainString() + ") different du resultat du compte de resultat (XI " + xi.toPlainString() + ")");
            controles.add(StatementControls.ecartDeResultat(entrepriseId, exerciceId, StatementType.BILAN_PASSIF, n.resultat(), xi));
        }
        BigDecimal totalActif = n.net("BZ");
        BigDecimal totalPassif = n.passif("DZ");
        if (raisons.isEmpty() && totalActif.compareTo(totalPassif) != 0) {
            raisons.add("BZ (" + totalActif.toPlainString() + ") different de DZ (" + totalPassif.toPlainString() + ")");
            controles.add(StatementControls.desequilibreBilan(entrepriseId, exerciceId, totalActif, totalPassif));
        }
        Status statut = raisons.isEmpty() ? Status.READY : Status.INCOMPLETE;

        var dataset = cache.empreinte(entrepriseId, exerciceId, exercice.getDateDebut(), exercice.getDateFin(), ReportingView.LEGAL_LEDGER);
        StatementResult actif = resultatActif(defActif, n, n1, soldes, statut, raisons, controles, dataset);
        StatementResult passif = resultatPassif(defPassif, n, n1, soldes, statut, raisons, controles, dataset, soldes.current(), mouvementsDeCloture(soldes.current(), activite.current()));
        return new BilanResult(actif, passif, statut, List.copyOf(raisons), List.copyOf(controles), totalActif, totalPassif, n.resultat(), xi);
    }

    /** Position issue d'une liste de soldes ; {@code ouverture} = soldes d'ouverture (position a la veille de l'exercice) au lieu des soldes de cloture. */
    Position position(FinancialStatementDefinition defActif, FinancialStatementDefinition defPassif, List<AccountBalance> soldes, Map<UUID, BigDecimal> cloture, boolean ouverture) {
        List<AccountInput> comptes = soldes.stream().map(s -> new AccountInput(s.accountId(), s.accountNumber(), s.accountLabel(), valeur(s, cloture, ouverture)))
                .filter(c -> c.net().signum() != 0).toList();
        MappingOutcome oa = resolver.resoudre(defActif, comptes);
        MappingOutcome op = resolver.resoudre(defPassif, comptes);

        Map<UUID, MappedAccount> mappesA = new HashMap<>();
        oa.mapped().forEach(m -> mappesA.put(m.account().id(), m));
        Map<UUID, MappedAccount> mappesP = new HashMap<>();
        op.mapped().forEach(m -> mappesP.put(m.account().id(), m));
        Set<UUID> ambigusIds = new HashSet<>();
        List<AmbiguousAccount> ambigus = new ArrayList<>();
        for (AmbiguousAccount a : oa.ambiguous()) {
            ambigus.add(a);
            ambigusIds.add(a.account().id());
        }
        for (AmbiguousAccount a : op.ambiguous()) {
            if (ambigusIds.add(a.account().id())) {
                ambigus.add(a);
            }
        }
        List<AccountInput> nonMappes = new ArrayList<>();
        for (AccountInput c : comptes) {
            boolean concerne = defActif.scope().concerne(c.number()) || defPassif.scope().concerne(c.number());
            if (!concerne || ambigusIds.contains(c.id())) {
                continue;
            }
            boolean dansA = mappesA.containsKey(c.id());
            boolean dansP = mappesP.containsKey(c.id());
            if (dansA && dansP) {
                Set<String> lignes = new LinkedHashSet<>(List.of(mappesA.get(c.id()).lineCode(), mappesP.get(c.id()).lineCode()));
                ambigus.add(new AmbiguousAccount(c, lignes));
                ambigusIds.add(c.id());
            } else if (!dansA && !dansP) {
                nonMappes.add(c);
            }
        }
        List<MappedAccount> listeA = mappesA.values().stream().filter(m -> !ambigusIds.contains(m.account().id())).toList();
        List<MappedAccount> listeP = mappesP.values().stream().filter(m -> !ambigusIds.contains(m.account().id())).toList();

        Map<String, BigDecimal> brutAlim = new HashMap<>();
        Map<String, BigDecimal> amortAlim = new HashMap<>();
        Map<String, BigDecimal> netAlim = new HashMap<>();
        for (StatementLineDefinition l : defActif.lines()) {
            if (l.calculationType() == CalculationType.MAPPED || l.calculationType() == CalculationType.STRUCTURE_ONLY) {
                brutAlim.put(l.code(), BigDecimal.ZERO);
                amortAlim.put(l.code(), BigDecimal.ZERO);
            }
        }
        for (MappedAccount m : listeA) {
            if (m.role() == MappingRole.GROSS) {
                brutAlim.merge(m.lineCode(), m.account().net(), BigDecimal::add);          // debit - credit
            } else {
                amortAlim.merge(m.lineCode(), m.account().net().negate(), BigDecimal::add);   // credit - debit
            }
        }
        brutAlim.forEach((code, b) -> netAlim.put(code, b.subtract(amortAlim.get(code))));
        Map<String, BigDecimal> brut = FormulaEvaluator.evaluerDefinition(defActif, brutAlim);
        Map<String, BigDecimal> amort = FormulaEvaluator.evaluerDefinition(defActif, amortAlim);
        Map<String, BigDecimal> net = FormulaEvaluator.evaluerDefinition(defActif, netAlim);

        BigDecimal resultat = BigDecimal.ZERO;
        for (AccountBalance s : soldes) {
            BigDecimal valeur = valeur(s, cloture, ouverture);
            String num = s.accountNumber();
            if (StatementAccounts.entreDansLeResultat(num)) {
                resultat = resultat.subtract(valeur);
            }
        }
        Map<String, BigDecimal> passifAlim = new HashMap<>();
        for (StatementLineDefinition l : defPassif.lines()) {
            if (l.calculationType() == CalculationType.MAPPED) {
                passifAlim.put(l.code(), BigDecimal.ZERO);
            } else if (l.calculationType() == CalculationType.RESULT_IDENTITY) {
                passifAlim.put(l.code(), resultat);
            }
        }
        for (MappedAccount m : listeP) {
            StatementLineDefinition l = ligne(defPassif, m.lineCode());
            BigDecimal contribution = l.signConvention() == SignConvention.ASSET_DEBIT ? m.account().net() : m.account().net().negate();
            passifAlim.merge(m.lineCode(), contribution, BigDecimal::add);
        }
        Map<String, BigDecimal> passif = FormulaEvaluator.evaluerDefinition(defPassif, passifAlim);

        Map<String, List<String>> raisonsA = raisons(defActif, brut, net);
        Map<String, List<String>> raisonsP = raisons(defPassif, passif, passif);
        return new Position(brut, amort, net, passif, resultat, listeA, listeP, nonMappes, ambigus, raisonsA, raisonsP);
    }

    /**
     * Mouvement de la periode porte par les ecritures de cloture (vue legale moins vue d'activite), par compte. Le Bilan est presente AVANT l'ecriture technique de cloture
     * (resultat en CJ, report a nouveau CH hors resultat de l'exercice) : sans cela un exercice cloture ferait figurer le resultat dans CH et plus dans CJ.
     */
    static Map<UUID, BigDecimal> mouvementsDeCloture(List<AccountBalance> legal, List<AccountBalance> activite) {
        Map<UUID, BigDecimal> parCompte = new HashMap<>();
        legal.forEach(s -> parCompte.put(s.accountId(), s.periodNet()));
        activite.forEach(s -> parCompte.merge(s.accountId(), s.periodNet().negate(), BigDecimal::add));
        parCompte.values().removeIf(v -> v.signum() == 0);
        return parCompte;
    }

    private static BigDecimal valeur(AccountBalance s, Map<UUID, BigDecimal> cloture, boolean ouverture) {
        return ouverture ? s.openingNet() : s.netBalance().subtract(cloture.getOrDefault(s.accountId(), BigDecimal.ZERO));
    }

    /** Raisons d'incompletude par ligne : ligne partiellement codee portant un solde ; une formule herite des raisons de ses operandes. */
    private static Map<String, List<String>> raisons(FinancialStatementDefinition def, Map<String, BigDecimal> brut, Map<String, BigDecimal> net) {
        Map<String, List<String>> resultat = new LinkedHashMap<>();
        for (StatementLineDefinition l : def.lines()) {
            raisonsDe(l.code(), def, brut, net, resultat);
        }
        return resultat;
    }

    private static List<String> raisonsDe(String code, FinancialStatementDefinition def, Map<String, BigDecimal> brut, Map<String, BigDecimal> net, Map<String, List<String>> memo) {
        if (memo.containsKey(code)) {
            return memo.get(code);
        }
        StatementLineDefinition l = ligne(def, code);
        List<String> raisons = new ArrayList<>();
        if (l.calculationType() == CalculationType.FORMULA) {
            for (String op : FormulaEvaluator.codesReferences(l.formula())) {
                for (String r : raisonsDe(op, def, brut, net, memo)) {
                    String reprise = op + " : " + (r.contains(" : ") ? r.substring(r.indexOf(" : ") + 3) : r);
                    if (!raisons.contains(reprise)) {
                        raisons.add(reprise);
                    }
                }
            }
        } else if (l.verificationStatus() != VerificationStatus.VERIFIED) {
            BigDecimal montant = brut.getOrDefault(code, BigDecimal.ZERO);
            if (montant.signum() != 0 || net.getOrDefault(code, BigDecimal.ZERO).signum() != 0) {
                raisons.add(code + " : composante reglementaire non codee (statut " + l.verificationStatus() + ")" + (l.remark() != null ? " - " + l.remark() : ""));
            }
        }
        memo.put(code, raisons);
        return raisons;
    }

    private static StatementLineDefinition ligne(FinancialStatementDefinition def, String code) {
        return def.lines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------------ presentation

    private StatementResult resultatActif(FinancialStatementDefinition def, Position n, Position n1, ExerciseBalances soldes, Status statut, List<String> raisons,
                                          List<ControlResult> controles, com.example.comptable.comptabilite.balance.DatasetVersion dataset) {
        ExerciceComptable exercice = soldes.exercice();
        List<Line> lignes = new ArrayList<>();
        for (StatementLineDefinition l : def.lines().stream().sorted(Comparator.comparingInt(StatementLineDefinition::order)).toList()) {
            List<LineAccount> comptes = new ArrayList<>();
            for (MappedAccount m : n.mappesActif()) {
                if (m.lineCode().equals(l.code())) {
                    BigDecimal montant = m.role() == MappingRole.GROSS ? m.account().net() : m.account().net().negate();
                    comptes.add(new LineAccount(m.account().id(), m.account().number(), m.account().label(), m.role(), montant, null));
                }
            }
            List<String> r = n.raisonsActif().getOrDefault(l.code(), List.of());
            lignes.add(new Line(l.code(), l.label(), l.order(), l.calculationType(), l.verificationStatus(), l.provenanceType(), n.net(l.code()),
                    n1 == null ? null : n1.net(l.code()), n.brut().get(l.code()), n.amort().get(l.code()), l.calculationBlockedReason(), comptes, r.isEmpty(), r));
        }
        return new StatementResult(StatementType.BILAN_ACTIF, def.framework(), def.version(), def.contentHash(), exercice.getId(), exercice.getDateDebut(), exercice.getDateFin(),
                soldes.previousExercice().map(ExerciceComptable::getId).orElse(null), soldes.previousExercice().map(ExerciceComptable::getDateDebut).orElse(null),
                soldes.previousExercice().map(ExerciceComptable::getDateFin).orElse(null), statut, List.copyOf(raisons), lignes,
                n.nonMappes().stream().map(a -> new AccountRef(a.id(), a.number(), a.label(), a.net())).toList(),
                n.ambigus().stream().map(a -> new Ambiguous(a.account().id(), a.account().number(), a.account().label(), List.copyOf(a.lineCodes()))).toList(),
                controles, dataset, OffsetDateTime.now());
    }

    private StatementResult resultatPassif(FinancialStatementDefinition def, Position n, Position n1, ExerciseBalances soldes, Status statut, List<String> raisons,
                                           List<ControlResult> controles, com.example.comptable.comptabilite.balance.DatasetVersion dataset, List<AccountBalance> soldesN, Map<UUID, BigDecimal> cloture) {
        ExerciceComptable exercice = soldes.exercice();
        List<Line> lignes = new ArrayList<>();
        for (StatementLineDefinition l : def.lines().stream().sorted(Comparator.comparingInt(StatementLineDefinition::order)).toList()) {
            List<LineAccount> comptes = new ArrayList<>();
            for (MappedAccount m : n.mappesPassif()) {
                if (m.lineCode().equals(l.code())) {
                    BigDecimal montant = l.signConvention() == SignConvention.ASSET_DEBIT ? m.account().net() : m.account().net().negate();
                    comptes.add(new LineAccount(m.account().id(), m.account().number(), m.account().label(), m.role(), montant, null));
                }
            }
            if (l.calculationType() == CalculationType.RESULT_IDENTITY) {
                for (AccountBalance s : soldesN) {
                    String num = s.accountNumber();
                    BigDecimal valeur = valeur(s, cloture, false);
                    if (valeur.signum() != 0 && StatementAccounts.entreDansLeResultat(num)) {
                        comptes.add(new LineAccount(s.accountId(), num, s.accountLabel(), MappingRole.GROSS, valeur.negate(), null));
                    }
                }
            }
            List<String> r = n.raisonsPassif().getOrDefault(l.code(), List.of());
            lignes.add(new Line(l.code(), l.label(), l.order(), l.calculationType(), l.verificationStatus(), l.provenanceType(), n.passif(l.code()),
                    n1 == null ? null : n1.passif(l.code()), null, null, l.calculationBlockedReason(), comptes, r.isEmpty(), r));
        }
        return new StatementResult(StatementType.BILAN_PASSIF, def.framework(), def.version(), def.contentHash(), exercice.getId(), exercice.getDateDebut(), exercice.getDateFin(),
                soldes.previousExercice().map(ExerciceComptable::getId).orElse(null), soldes.previousExercice().map(ExerciceComptable::getDateDebut).orElse(null),
                soldes.previousExercice().map(ExerciceComptable::getDateFin).orElse(null), statut, List.copyOf(raisons), lignes,
                n.nonMappes().stream().map(a -> new AccountRef(a.id(), a.number(), a.label(), a.net())).toList(),
                n.ambigus().stream().map(a -> new Ambiguous(a.account().id(), a.account().number(), a.account().label(), List.copyOf(a.lineCodes()))).toList(),
                controles, dataset, OffsetDateTime.now());
    }
}
