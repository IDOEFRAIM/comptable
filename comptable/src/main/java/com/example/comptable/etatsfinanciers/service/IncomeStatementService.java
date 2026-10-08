package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.comptabilite.balance.AccountBalance;
import com.example.comptable.comptabilite.balance.AccountingBalanceEngine;
import com.example.comptable.comptabilite.balance.ExerciseBalances;
import com.example.comptable.comptabilite.balance.ReportingView;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.CalculationType;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.FormulaEvaluator;
import com.example.comptable.etatsfinanciers.definition.StatementLineDefinition;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.definition.VerificationStatus;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.AccountInput;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compte de resultat SYSCOHADA (Systeme normal), phases F3 + F6. Pipeline unique : ecritures -&gt; {@link AccountingBalanceEngine} (vue {@code PERIOD_ACTIVITY} :
 * l'ecriture technique de cloture ne fausse pas l'activite) -&gt; {@link FinancialStatementMappingResolver} (regles de la definition versionnee) -&gt; formules de la
 * definition -&gt; etat. Aucun test de prefixe de compte ici ; montants {@link BigDecimal} ; chaque ligne porte N (exercice demande) et N-1 (exercice precedent REEL).
 */
@Service
@RequiredArgsConstructor
public class IncomeStatementService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final FinancialStatementDefinitionRegistry registry;
    private final AccountingBalanceEngine balanceEngine;
    private final StatementComputationCache cache;
    private final FinancialStatementMappingResolver resolver;

    @Transactional(readOnly = true)
    public StatementResult generer(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return calculer(entrepriseId, exerciceId);
    }

    /** Calcule l'etat sans controle d'acces (l'appelant l'a fait) : utilise aussi par la finalisation. */
    StatementResult calculer(UUID entrepriseId, UUID exerciceId) {
        return cache.enCache(java.util.List.of("CR", entrepriseId, exerciceId), () -> calculerSansCache(entrepriseId, exerciceId));
    }

    private StatementResult calculerSansCache(UUID entrepriseId, UUID exerciceId) {
        FinancialStatementDefinition definition = registry.courante(StatementType.COMPTE_RESULTAT);
        ExerciseBalances soldes = cache.soldes(entrepriseId, exerciceId, ReportingView.PERIOD_ACTIVITY);
        ExerciceComptable exercice = soldes.exercice();

        Calcul courant = calcul(definition, soldes.current());
        Calcul precedent = soldes.previousExercice().isPresent() ? calcul(definition, soldes.previous()) : null;

        List<Line> lignes = new ArrayList<>();
        for (StatementLineDefinition ligne : definition.lines().stream().sorted(Comparator.comparingInt(StatementLineDefinition::order)).toList()) {
            List<LineAccount> comptes = new ArrayList<>();
            if (ligne.calculationType() == CalculationType.MAPPED) {
                for (MappedAccount m : courant.resultat.pourLigne(ligne.code())) {
                    BigDecimal precedentMontant = precedent == null ? null : precedent.montantParCompte.get(m.account().id());
                    comptes.add(new LineAccount(m.account().id(), m.account().number(), m.account().label(), m.role(), m.account().net().negate(),
                            precedent == null ? null : (precedentMontant == null ? BigDecimal.ZERO : precedentMontant)));
                }
            }
            lignes.add(new Line(ligne.code(), ligne.label(), ligne.order(), ligne.calculationType(), ligne.verificationStatus(), ligne.provenanceType(),
                    courant.valeurs.get(ligne.code()), precedent == null ? null : precedent.valeurs.get(ligne.code()), null, null, ligne.calculationBlockedReason(), comptes, true, List.of()));
        }

        List<ControlResult> controles = new ArrayList<>();
        if (!courant.resultat.unmapped().isEmpty()) {
            controles.add(StatementControls.comptesNonMappes(entrepriseId, exerciceId, StatementType.COMPTE_RESULTAT, courant.resultat.unmapped(), null));
        }
        if (!courant.resultat.ambiguous().isEmpty()) {
            controles.add(StatementControls.correspondanceAmbigue(entrepriseId, exerciceId, StatementType.COMPTE_RESULTAT, courant.resultat.ambiguous()));
        }
        BigDecimal resultatEtat = courant.valeurs.get("XI");
        if (resultatEtat != null && resultatEtat.compareTo(courant.resultatGrandLivre) != 0) {
            controles.add(StatementControls.ecartDeResultat(entrepriseId, exerciceId, StatementType.COMPTE_RESULTAT, resultatEtat, courant.resultatGrandLivre));
        }

        List<String> raisons = new ArrayList<>();
        if (!courant.resultat.unmapped().isEmpty()) {
            raisons.add(courant.resultat.unmapped().size() + " compte(s) non mappe(s) : etat incomplet");
        }
        if (!courant.resultat.ambiguous().isEmpty()) {
            raisons.add(courant.resultat.ambiguous().size() + " compte(s) a correspondance ambigue");
        }
        if (resultatEtat != null && resultatEtat.compareTo(courant.resultatGrandLivre) != 0) {
            raisons.add("ecart entre le resultat de l'etat et celui du grand livre");
        }
        for (StatementLineDefinition ligne : definition.lines()) {
            if (ligne.calculationType() != CalculationType.STRUCTURE_ONLY && ligne.verificationStatus() != VerificationStatus.VERIFIED) {
                raisons.add("la ligne " + ligne.code() + " n'est que " + ligne.verificationStatus() + " (provenance " + ligne.provenanceType() + ") : " + ligne.remark());
            }
        }

        List<AccountRef> nonMappes = courant.resultat.unmapped().stream().map(a -> new AccountRef(a.id(), a.number(), a.label(), a.net())).toList();
        List<Ambiguous> ambigus = courant.resultat.ambiguous().stream()
                .map(a -> new Ambiguous(a.account().id(), a.account().number(), a.account().label(), List.copyOf(a.lineCodes()))).toList();

        var dataset = cache.empreinte(entrepriseId, exerciceId, exercice.getDateDebut(), exercice.getDateFin(), ReportingView.PERIOD_ACTIVITY);
        return new StatementResult(StatementType.COMPTE_RESULTAT, definition.framework(), definition.version(), definition.contentHash(), exerciceId, exercice.getDateDebut(),
                exercice.getDateFin(), soldes.previousExercice().map(ExerciceComptable::getId).orElse(null), soldes.previousExercice().map(ExerciceComptable::getDateDebut).orElse(null),
                soldes.previousExercice().map(ExerciceComptable::getDateFin).orElse(null), raisons.isEmpty() ? Status.READY : Status.DRAFT, raisons, lignes, nonMappes, ambigus,
                controles, dataset, OffsetDateTime.now());
    }

    /** Montants par ligne, resultat du grand livre et montants par compte pour une serie de soldes d'activite. */
    private record Calcul(MappingOutcome resultat, Map<String, BigDecimal> valeurs, Map<UUID, BigDecimal> montantParCompte, BigDecimal resultatGrandLivre) {
    }

    private Calcul calcul(FinancialStatementDefinition definition, List<AccountBalance> soldes) {
        List<AccountInput> comptes = soldes.stream().filter(s -> s.periodNet().signum() != 0)
                .map(s -> new AccountInput(s.accountId(), s.accountNumber(), s.accountLabel(), s.periodNet())).toList();
        MappingOutcome resultat = resolver.resoudre(definition, comptes);

        Map<String, BigDecimal> alimentees = new HashMap<>();
        definition.lines().stream().filter(l -> l.calculationType() == CalculationType.MAPPED).forEach(l -> alimentees.put(l.code(), BigDecimal.ZERO));
        Map<UUID, BigDecimal> parCompte = new HashMap<>();
        for (MappedAccount m : resultat.mapped()) {
            BigDecimal contribution = m.account().net().negate();   // credit - debit
            alimentees.merge(m.lineCode(), contribution, BigDecimal::add);
            parCompte.put(m.account().id(), contribution);
        }
        BigDecimal resultatGrandLivre = comptes.stream().filter(c -> definition.scope().concerne(c.number())).map(c -> c.net().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> valeurs = new LinkedHashMap<>(FormulaEvaluator.evaluerDefinition(definition, alimentees));
        return new Calcul(resultat, valeurs, parCompte, resultatGrandLivre);
    }

    /** Controle d'acces + entreprise : utilitaire pour la publication des constats. */
    Entreprise entreprise(UUID entrepriseId, UtilisateurPrincipal principal) {
        return entrepriseAccessService.verifierAcces(entrepriseId, principal);
    }
}
