package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.comptabilite.balance.AccountBalance;
import com.example.comptable.comptabilite.balance.AccountingBalanceEngine;
import com.example.comptable.comptabilite.balance.ExerciseBalances;
import com.example.comptable.comptabilite.balance.ReportingView;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteLine;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NoteTable;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NotesCatalogue;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.AccountInput;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService.Position;
import com.example.comptable.etatsfinanciers.service.StatementResult.Line;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Notes annexes (phase F8). Moteur de generation des notes dont le contenu calculable est defini par le catalogue versionne : tableaux de comptes (soldes de cloture ou activite de la
 * periode) et lectures de lignes des etats ({@code STATEMENT_LINES}) avec dependance explicite sur l'etat source (Compte de resultat, Bilan, TFT).
 *
 * <p><b>Applicabilite</b> : {@code APPLICABLE} (decision, regle imprimee, ou donnees presentes), {@code NOT_APPLICABLE} (decision, ou regle imprimee), {@code NOT_APPLICABLE_CANDIDATE} (absence
 * PROUVEE par les soldes et mouvements, en attente de validation humaine : une seule validation globale suffit), {@code UNDETERMINED} (regle imprimee sans entree : Note 35 sans effectif),
 * {@code TO_DETERMINE} (aucune preuve possible, notamment pour les informations qualitatives extra-comptables : engagements, litiges, retraites, evenements). L'absence de solde ne prouve
 * jamais l'absence d'information qualitative : seules les notes entierement calculables par les comptes peuvent devenir candidates.</p>
 *
 * <p><b>Statut</b> d'une note : {@code NOT_APPLICABLE} (decidee), {@code READY}, {@code INCOMPLETE} (calcul impossible ou source non prete : comptes non places, tableau non genere,
 * etat source non READY) ou {@code MANUAL_REQUIRED} (information humaine requise : section manuelle, validation d'applicabilite). Les Notes sont {@code READY} si chaque note est
 * {@code READY} ou {@code NOT_APPLICABLE}.</p>
 */
@Service
@RequiredArgsConstructor
public class NotesService {

    /** Notes dont l'absence d'objet est prouvable par les soldes de rubriques du Bilan (pas d'information qualitative extra-comptable dans leur contenu). */
    private static final Map<String, List<String>> ABSENCE_PAR_RUBRIQUE = Map.of(
            "4", List.of("AR", "AS"),
            "6", List.of("BB"),
            "9", List.of("BQ"));

    public record Decisions(Integer effectif, Map<String, String> applicabilites, boolean validerCandidates) {
        public Decisions {
            applicabilites = applicabilites == null ? Map.of() : Map.copyOf(applicabilites);
        }

        public Decisions(Integer effectif, Map<String, String> applicabilites) {
            this(effectif, applicabilites, false);
        }

        public static Decisions aucune() {
            return new Decisions(null, Map.of(), false);
        }
    }

    public record NoteLineResult(String code, String label, String kind, BigDecimal amountN, BigDecimal amountNMinus1, List<String> accounts) {
    }

    /** Information manquante d'une note : section concernee et nature du manque (COMPUTED : calcul impossible ; MANUAL : saisie humaine ; VALIDATION : decision d'applicabilite). */
    public record NoteGap(String noteCode, String section, String missingInformation, String kind, List<String> fieldCodes) {
        public NoteGap {
            fieldCodes = fieldCodes == null ? List.of() : List.copyOf(fieldCodes);
        }

        public NoteGap(String noteCode, String section, String missingInformation, String kind) {
            this(noteCode, section, missingInformation, kind, List.of());
        }
    }

    public record NoteResult(String code, String title, String applicability, boolean attached, String status, String automationType, String readiness,
                             List<String> autoCalculatedSections, List<String> manualRequiredSections, String completion, List<NoteLineResult> lines, List<NoteGap> gaps,
                             List<String> reasons, String schemaStatus, List<NoteCompletionEvaluator.SectionState> sections, List<String> missingSections) {
    }

    public record NotesResult(String framework, String version, String definitionHash, UUID exerciceId, Status status, List<String> statusReasons, List<NoteResult> notes,
                              List<ControlResult> controls, OffsetDateTime generatedAt) {
        public NotesResult avecStatut(Status nouveau) {
            return new NotesResult(framework, version, definitionHash, exerciceId, nouveau, statusReasons, notes, controls, generatedAt);
        }
    }

    private final EntrepriseAccessService entrepriseAccessService;
    private final FinancialStatementDefinitionRegistry registry;
    private final AccountingBalanceEngine balanceEngine;
    private final StatementComputationCache cache;
    private final FinancialStatementMappingResolver resolver;
    private final IncomeStatementService incomeStatementService;
    private final BalanceSheetService balanceSheetService;
    private final CashFlowStatementService cashFlowStatementService;

    @Transactional(readOnly = true)
    public NotesResult generer(UUID entrepriseId, UUID exerciceId, Decisions decisions, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return calculer(entrepriseId, exerciceId, decisions);
    }

    /** Etats sources calcules a la demande (une seule fois par generation) ; le Compte de resultat sert aussi a decider l'applicabilite des notes qu'il alimente. */
    private final class Sources {
        private final UUID entrepriseId;
        private final UUID exerciceId;
        private StatementResult cr;
        private BalanceSheetService.BilanResult bilan;
        private StatementResult tft;

        Sources(UUID entrepriseId, UUID exerciceId) {
            this.entrepriseId = entrepriseId;
            this.exerciceId = exerciceId;
        }

        StatementResult etat(String source) {
            return switch (source) {
                case "COMPTE_RESULTAT" -> cr != null ? cr : (cr = incomeStatementService.calculer(entrepriseId, exerciceId));
                case "BILAN_ACTIF" -> bilan().actif();
                case "BILAN_PASSIF" -> bilan().passif();
                case "TFT" -> tft != null ? tft : (tft = cashFlowStatementService.calculer(entrepriseId, exerciceId));
                default -> throw new IllegalArgumentException("Source inconnue : " + source);
            };
        }

        private BalanceSheetService.BilanResult bilan() {
            return bilan != null ? bilan : (bilan = balanceSheetService.calculer(entrepriseId, exerciceId));
        }
    }

    NotesResult calculer(UUID entrepriseId, UUID exerciceId, Decisions decisions) {
        return calculer(entrepriseId, exerciceId, decisions, Map.of());
    }

    /** {@code manuelles} : donnees saisies par note (jamais modifiees ici). L'effectif de la Note 35 est lu de sa section {@code effectif} s'il n'est pas fourni par {@code decisions}. */
    public NotesResult calculer(UUID entrepriseId, UUID exerciceId, Decisions decisions, Map<String, NoteManualData> manuelles) {
        NotesCatalogue catalogue = registry.notes();
        if (decisions.effectif() == null) {
            NoteManualData m35 = manuelles.get("35");
            NoteManualData.ManualValue v = m35 == null ? null : m35.valeur("effectif", "effectif");
            if (v != null && v.value() != null && !v.value().isBlank()) {
                try {
                    decisions = new Decisions(Integer.valueOf(v.value().trim()), decisions.applicabilites(), decisions.validerCandidates());
                } catch (NumberFormatException e) {
                    // valeur invalide : refusee a la saisie ; ici l'effectif reste inconnu
                }
            }
        }
        ExerciseBalances legal = cache.soldes(entrepriseId, exerciceId, ReportingView.LEGAL_LEDGER);
        ExerciseBalances activite = cache.soldes(entrepriseId, exerciceId, ReportingView.PERIOD_ACTIVITY);
        Sources sources = new Sources(entrepriseId, exerciceId);
        Set<String> rubriquesAvecActivite = null;
        Map<String, Boolean> absence = new HashMap<>();
        if (catalogue.notes().stream().anyMatch(n -> ABSENCE_PAR_RUBRIQUE.containsKey(n.code()))) {
            rubriquesAvecActivite = rubriquesAvecActivite(legal, activite);
            for (var e : ABSENCE_PAR_RUBRIQUE.entrySet()) {
                final Set<String> actives = rubriquesAvecActivite;
                absence.put(e.getKey(), e.getValue().stream().noneMatch(actives::contains));
            }
        }

        List<NoteResult> notes = new ArrayList<>();
        List<ControlResult> controles = new ArrayList<>();
        List<String> raisons = new ArrayList<>();
        for (NoteDefinition note : catalogue.notes()) {
            NoteResult r = note(note, decisions, legal, activite, sources, absence, manuelles.getOrDefault(note.code(), NoteManualData.vide()));
            notes.add(r);
            if (r.attached() && !"READY".equals(r.status())) {
                controles.add(StatementControls.noteIncomplete(entrepriseId, exerciceId, note.code(), note.title(), r.gaps()));
            }
            if (!"READY".equals(r.status()) && !"NOT_APPLICABLE".equals(r.status())) {
                raisons.add("Note " + note.code() + " : " + r.status() + " (" + r.applicability() + ")");
            }
        }
        return new NotesResult(catalogue.framework(), catalogue.version(), catalogue.contentHash(), exerciceId, raisons.isEmpty() ? Status.READY : Status.INCOMPLETE,
                List.copyOf(raisons), List.copyOf(notes), List.copyOf(controles), OffsetDateTime.now());
    }

    // ------------------------------------------------------------------------------------------------ une note

    private NoteResult note(NoteDefinition note, Decisions decisions, ExerciseBalances legal, ExerciseBalances activite, Sources sources, Map<String, Boolean> absence,
                            NoteManualData manuel) {
        boolean implemente = note.table() != null && "IMPLEMENTED".equals(note.readiness());
        boolean surEtats = implemente && "STATEMENT_LINES".equals(note.table().basis());
        List<NoteLineResult> lignes = List.of();
        List<NoteGap> gaps = new ArrayList<>();
        boolean donnees = false;
        boolean applicableAvantCalcul = isApplicableParDecisionOuRegle(note, decisions);

        if (implemente && !surEtats) {
            Calcul n = calcul(note.table(), legal.current(), activite.current());
            Calcul n1 = legal.previousExercice().isPresent() ? calcul(note.table(), legal.previous(), activite.previous()) : null;
            donnees = n.donnees || (n1 != null && n1.donnees);
            n.raisons.forEach(r -> gaps.add(new NoteGap(note.code(), "tableau chiffre", r, "COMPUTED")));
            lignes = resultats(note.table(), n, n1);
        } else if (surEtats && (applicableAvantCalcul || !"34".equals(note.code()))) {
            Map<String, BigDecimal[]> valeurs = new LinkedHashMap<>();
            Set<String> sourcesNonPretes = new java.util.TreeSet<>();
            for (NoteLine l : note.table().lines()) {
                String[] ref = l.formula().split(":");
                StatementResult etat = sources.etat(ref[0]);
                Line ligne = etat.lines().stream().filter(x -> x.code().equals(ref[1])).findFirst().orElseThrow();
                BigDecimal facteur = "DEBIT_POSITIVE".equals(l.sign()) ? BigDecimal.ONE.negate() : BigDecimal.ONE;
                if (ligne.amountN() == null || etat.status() != Status.READY) {
                    sourcesNonPretes.add(ref[0] + " " + etat.status());
                }
                valeurs.put(l.code(), new BigDecimal[]{ligne.amountN() == null ? null : ligne.amountN().multiply(facteur),
                        ligne.amountNMinus1() == null ? null : ligne.amountNMinus1().multiply(facteur)});
                donnees = donnees || (ligne.amountN() != null && ligne.amountN().signum() != 0) || (ligne.amountNMinus1() != null && ligne.amountNMinus1().signum() != 0);
            }
            sourcesNonPretes.forEach(s -> gaps.add(new NoteGap(note.code(), "etat source", "etat source non READY : " + s, "COMPUTED")));
            List<NoteLineResult> res = new ArrayList<>();
            for (NoteLine l : note.table().lines()) {
                BigDecimal[] v = valeurs.get(l.code());
                res.add(new NoteLineResult(l.code(), l.label(), l.kind(), sourcesNonPretes.isEmpty() ? v[0] : null, v[1], List.of()));
            }
            lignes = res;
        }

        String applicabilite = applicabilite(note, decisions, implemente, donnees, absence);
        boolean jointe = applicabilite.equals("APPLICABLE");
        NoteCompletionEvaluator.Evaluation eval = NoteCompletionEvaluator.evaluer(note, applicabilite, gaps, implemente, manuel);
        List<NoteGap> tous = eval.gaps();
        long requis = eval.sections().stream().filter(s -> !"GENERATED".equals(s.sourceType()) && s.required()).count();
        long manquants = eval.missingSections().size();
        long generees = eval.sections().stream().filter(s -> "GENERATED".equals(s.sourceType()) && "GENERATED".equals(s.status())).count();
        long total = requis + eval.sections().stream().filter(s -> "GENERATED".equals(s.sourceType())).count();
        String completion = total == 0 ? "" : ((generees + requis - manquants) * 100 / total) + " % complete, " + manquants + " section(s) a completer";
        List<String> raisons = tous.stream().map(g -> g.section() + " : " + g.missingInformation()).toList();
        return new NoteResult(note.code(), note.title(), applicabilite, jointe, eval.status(), note.automationType(), note.readiness(), note.autoCalculatedSections(),
                note.manualRequiredSections(), completion, lignes, List.copyOf(tous), raisons, note.schemaStatus(), eval.sections(), eval.missingSections());
    }

    private static boolean isApplicableParDecisionOuRegle(NoteDefinition note, Decisions decisions) {
        return "APPLICABLE".equals(decisions.applicabilites().get(note.code()));
    }

    private static String applicabilite(NoteDefinition note, Decisions decisions, boolean implemente, boolean donnees, Map<String, Boolean> absence) {
        String decision = decisions.applicabilites().get(note.code());
        if ("APPLICABLE".equals(decision) || "NOT_APPLICABLE".equals(decision)) {
            return decision;
        }
        if (note.applicabilityRule() != null && "MIN_HEADCOUNT_EXCLUSIVE".equals(note.applicabilityRule().type())) {
            return decisions.effectif() == null ? "UNDETERMINED" : decisions.effectif() > note.applicabilityRule().threshold() ? "APPLICABLE" : "NOT_APPLICABLE";
        }
        if ("34".equals(note.code())) {
            return "TO_DETERMINE";      // fiche de synthese : applicabilite par entite, jamais deduite
        }
        if (implemente && donnees) {
            return "APPLICABLE";
        }
        Boolean absente = absence.get(note.code());
        if (absente != null) {
            return absente ? candidatOuValide(decisions) : "APPLICABLE";
        }
        // tableau entierement calcule par les comptes, sans donnee : absence prouvee ; une section manuelle qui releve d'une information
        // extra-comptable (ex. interets non comptabilises) interdit de conclure a l'absence
        boolean ventilationSeulement = note.table() != null && "STATEMENT_LINES".equals(note.table().basis());
        boolean infoExtraComptable = note.manualRequiredSections().stream().anyMatch(s -> !s.contains("conditionnel")) && !ventilationSeulement;
        if (implemente && !infoExtraComptable) {
            return candidatOuValide(decisions);
        }
        return "TO_DETERMINE";
    }

    private static String candidatOuValide(Decisions decisions) {
        return decisions.validerCandidates() ? "NOT_APPLICABLE" : "NOT_APPLICABLE_CANDIDATE";
    }

    // ------------------------------------------------------------------------------------------------ preuve d'absence (rubriques du Bilan)

    /** Rubriques de l'Actif portant un solde (ouverture, cloture) ou un mouvement dans l'exercice, quel que soit le role du compte (brut ou depreciation). */
    private Set<String> rubriquesAvecActivite(ExerciseBalances legal, ExerciseBalances activite) {
        FinancialStatementDefinition defActif = registry.courante(StatementType.BILAN_ACTIF);
        FinancialStatementDefinition defPassif = registry.courante(StatementType.BILAN_PASSIF);
        Set<String> actives = new HashSet<>();
        Map<UUID, BigDecimal> cloture = BalanceSheetService.mouvementsDeCloture(legal.current(), activite.current());
        for (boolean ouverture : new boolean[]{true, false}) {
            Position p = balanceSheetService.position(defActif, defPassif, legal.current(), ouverture ? Map.of() : cloture, ouverture);
            p.mappesActif().forEach(m -> actives.add(m.lineCode()));
        }
        List<AccountInput> mouvements = activite.current().stream().filter(s -> s.periodDebit().signum() != 0 || s.periodCredit().signum() != 0)
                .map(s -> new AccountInput(s.accountId(), s.accountNumber(), s.accountLabel(), BigDecimal.ONE)).toList();
        resolver.resoudre(defActif, mouvements).mapped().forEach(m -> actives.add(m.lineCode()));
        return actives;
    }

    // ------------------------------------------------------------------------------------------------ tableaux de comptes

    private record Calcul(Map<String, BigDecimal> valeurs, Map<String, List<String>> comptes, List<String> raisons, boolean donnees) {
    }

    private static List<NoteLineResult> resultats(NoteTable table, Calcul n, Calcul n1) {
        List<NoteLineResult> resultat = new ArrayList<>();
        for (NoteLine l : table.lines()) {
            resultat.add(new NoteLineResult(l.code(), l.label(), l.kind(), n.valeurs.get(l.code()), n1 == null ? null : n1.valeurs.get(l.code()), n.comptes.getOrDefault(l.code(), List.of())));
        }
        return resultat;
    }

    /** Formule de note : codes de lignes separes par + ou - (ex. a1 + a2 - a4) ; une operande absente rend le total inconnu. */
    private static BigDecimal somme(String formule, Map<String, BigDecimal> valeurs) {
        BigDecimal total = BigDecimal.ZERO;
        boolean plus = true;
        for (String jeton : formule.trim().split("\\s+")) {
            if (jeton.equals("+")) {
                plus = true;
            } else if (jeton.equals("-")) {
                plus = false;
            } else {
                BigDecimal v = valeurs.get(jeton);
                if (v == null) {
                    return null;
                }
                total = plus ? total.add(v) : total.subtract(v);
            }
        }
        return total;
    }

    private Calcul calcul(NoteTable table, List<AccountBalance> legal, List<AccountBalance> activite) {
        boolean solde = "BALANCE".equals(table.basis());
        List<AccountBalance> source = solde ? legal : activite;
        Map<String, BigDecimal> alimentees = new LinkedHashMap<>();
        Map<String, List<String>> comptes = new HashMap<>();
        List<String> raisons = new ArrayList<>();
        boolean donnees = false;
        for (NoteLine l : table.lines()) {
            if ("LINE".equals(l.kind())) {
                alimentees.put(l.code(), BigDecimal.ZERO);
            }
        }
        for (AccountBalance s : source) {
            BigDecimal valeur = solde ? s.netBalance() : s.periodNet();
            if (valeur.signum() == 0 && (solde || (s.periodDebit().signum() == 0 && s.periodCredit().signum() == 0))) {
                continue;
            }
            String num = s.accountNumber();
            if (table.domainPrefixes().stream().noneMatch(num::startsWith) || table.excludedFromDomain().stream().anyMatch(num::startsWith)) {
                continue;
            }
            donnees = true;
            List<NoteLine> candidates = table.lines().stream().filter(l -> "LINE".equals(l.kind()) && l.includePrefixes().stream().anyMatch(num::startsWith)
                    && l.excludePrefixes().stream().noneMatch(num::startsWith)).toList();
            if (candidates.isEmpty()) {
                raisons.add("compte " + num + " du perimetre de la note place dans aucune ligne");
            } else if (candidates.size() > 1) {
                raisons.add("compte " + num + " accepte par plusieurs lignes de la note");
            } else {
                NoteLine l = candidates.get(0);
                BigDecimal contribution = "CREDIT_POSITIVE".equals(l.sign()) ? valeur.negate() : valeur;
                alimentees.merge(l.code(), contribution, BigDecimal::add);
                comptes.computeIfAbsent(l.code(), k -> new ArrayList<>()).add(num);
            }
        }
        Map<String, BigDecimal> valeurs = new LinkedHashMap<>(alimentees);
        for (NoteLine l : table.lines()) {
            if ("TOTAL".equals(l.kind())) {
                valeurs.put(l.code(), somme(l.formula(), valeurs));
            }
        }
        return new Calcul(valeurs, comptes, raisons, donnees);
    }
}
