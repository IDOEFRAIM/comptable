package com.example.comptable.finance;

import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.forecast.PaymentBehaviorService;
import com.example.comptable.finance.forecast.RecurringFlowDetector;
import com.example.comptable.finance.forecast.domain.ForecastConfidence;
import com.example.comptable.finance.forecast.domain.LiquidityEventType;
import com.example.comptable.finance.forecast.domain.LiquiditySeverity;
import com.example.comptable.finance.forecast.web.dto.CashForecastDto;
import com.example.comptable.finance.forecast.web.dto.CashForecastWeekDto;
import com.example.comptable.finance.forecast.web.dto.LiquidityEventDto;
import com.example.comptable.finance.forecast.web.dto.PaymentBehaviorDto;
import com.example.comptable.finance.forecast.web.dto.RecurringFlowDto;
import com.example.comptable.finance.web.dto.PrevisionTresorerieDto;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * "Cash Forecasting" (mission P1, section 23-25) : horizons 7/30/90 jours. Palier KNOWN =
 * factures clients dont l'echeance tombe dans l'horizon (montant ET date certains) + declarations
 * fiscales dues dans l'horizon (decaissement certain). Palier EXPECTED = factures clients deja en
 * retard, ponderees par une probabilite de recouvrement deterministe basee sur l'anciennete du
 * retard (pas un modele statistique entraine - aucune donnee de paiement historique n'existe
 * encore pour en calibrer un reellement, voir docs/P1_ARCHITECTURE.md). Les paiements fournisseurs
 * ne sont volontairement pas inclus : {@code FactureFournisseur} n'a aucune colonne d'echeance
 * (limite deja documentee en P0), impossible de les dater sans inventer une donnee.
 */
@Service
@RequiredArgsConstructor
public class CashForecastService {

    private static final int[] HORIZONS_JOURS = {7, 30, 90};

    private static final int BORNE_TRANCHE_1 = 30;
    private static final int BORNE_TRANCHE_2 = 60;
    private static final int BORNE_TRANCHE_3 = 90;
    private static final BigDecimal PROBABILITE_TRANCHE_1 = new BigDecimal("0.90");
    private static final BigDecimal PROBABILITE_TRANCHE_2 = new BigDecimal("0.70");
    private static final BigDecimal PROBABILITE_TRANCHE_3 = new BigDecimal("0.50");
    private static final BigDecimal PROBABILITE_TRANCHE_4 = new BigDecimal("0.20");

    // --- Constantes du 13-week cash forecast (mission P2, section 5+ du plan) ---
    private static final int SEMAINES_PAR_DEFAUT = 13;
    // Borne haute du parametre client nombreSemaines : un "13-week cash forecast" ne va jamais
    // au-dela d'un an, et sans ce plafond une valeur du type 100000 construit 100000 DTO/semaine.
    private static final int SEMAINES_MAX = 52;
    private static final int MOIS_HISTORIQUE_RECURRENCE = 6;
    // Une charge "recurrente" mensuelle dont la derniere occurrence remonte a plus de 2 mois est
    // consideree resiliee : on ne projette plus de sorties fantomes pour un abonnement arrete.
    private static final int MOIS_INACTIVITE_RECURRENCE_MAX = 2;
    private static final long DELAI_PAR_DEFAUT_SANS_HISTORIQUE_JOURS = 30;
    private static final int SEMAINES_HISTORIQUE_MINIMUM = 4;
    private static final int SEMAINES_HISTORIQUE_NORMALE = 13;
    private static final int SEMAINES_HISTORIQUE_FORTE = 26;
    private static final BigDecimal MULTIPLE_SORTIE_EXCEPTIONNELLE = new BigDecimal("2");
    private static final long RETARD_MOYEN_A_RISQUE_JOURS = 30;

    private final EntrepriseAccessService entrepriseAccessService;
    private final EntrepriseRepository entrepriseRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final FactureRepository factureRepository;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;
    private final PaymentBehaviorService paymentBehaviorService;
    private final com.example.comptable.comptabilite.allocation.StatutPaiementFactureService statutPaiementFactureService;

    @Transactional(readOnly = true)
    public List<PrevisionTresorerieDto> generer(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate aujourdHui = LocalDate.now();

        BigDecimal soldeActuel = ligneEcritureRepository.soldeTresorerie(entrepriseId, aujourdHui);
        // Le reste a encaisser vient des allocations de reglements (source unique du statut de paiement), pas d'un drapeau.
        BigDecimal encaissementsAttendus = ponderer(statutPaiementFactureService.ouvertes(entrepriseId, factureRepository
                .findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                        entrepriseId, TypeFacture.FACTURE, StatutFacture.VALIDEE, aujourdHui)), aujourdHui);

        return Arrays.stream(HORIZONS_JOURS)
                .mapToObj(horizon -> construire(entrepriseId, aujourdHui, horizon, soldeActuel, encaissementsAttendus))
                .toList();
    }

    private PrevisionTresorerieDto construire(UUID entrepriseId, LocalDate aujourdHui, int horizonJours,
                                               BigDecimal soldeActuel, BigDecimal encaissementsAttendus) {
        LocalDate limite = aujourdHui.plusDays(horizonJours);

        BigDecimal encaissementsConnus = statutPaiementFactureService.ouvertes(entrepriseId, factureRepository
                .findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                        entrepriseId, TypeFacture.FACTURE, StatutFacture.VALIDEE, aujourdHui, limite))
                .stream()
                .map(com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte::reste)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal decaissementsConnus = declarationFiscaleRepository
                .findByEntrepriseIdAndDateEcheanceBetween(entrepriseId, aujourdHui, limite)
                .stream()
                .map(DeclarationFiscale::getMontant)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal soldePrevisionnel = soldeActuel
                .add(encaissementsConnus)
                .add(encaissementsAttendus)
                .subtract(decaissementsConnus);

        return new PrevisionTresorerieDto(aujourdHui, horizonJours, soldeActuel, encaissementsConnus,
                encaissementsAttendus, decaissementsConnus, soldePrevisionnel);
    }

    /** Ponderation deterministe (pas de LLM, pas de modele statistique) par anciennete de retard -
     * memes bornes que {@code BalanceAgeeService} pour rester coherent avec le reste du systeme. */
    private BigDecimal ponderer(List<com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte> facturesEnRetard,
                                LocalDate aujourdHui) {
        return facturesEnRetard.stream()
                .map(f -> {
                    long joursRetard = ChronoUnit.DAYS.between(f.facture().getDateEcheance(), aujourdHui);
                    BigDecimal probabilite = probabiliteRecouvrement(joursRetard);
                    return f.reste().multiply(probabilite).setScale(0, RoundingMode.HALF_UP);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal probabiliteRecouvrement(long joursRetard) {
        if (joursRetard <= BORNE_TRANCHE_1) {
            return PROBABILITE_TRANCHE_1;
        }
        if (joursRetard <= BORNE_TRANCHE_2) {
            return PROBABILITE_TRANCHE_2;
        }
        if (joursRetard <= BORNE_TRANCHE_3) {
            return PROBABILITE_TRANCHE_3;
        }
        return PROBABILITE_TRANCHE_4;
    }

    // =========================================================================================
    // 13-WEEK CASH FORECAST (mission P2, "Cash Flow Intelligence") - fait evoluer ce service
    // existant (jamais un second CashForecastService concurrent) : reutilise les memes sources
    // de donnees que generer()/construire() ci-dessus (soldeTresorerie, factures, declarations
    // fiscales), le meme mecanisme de ponderation par anciennete (probabiliteRecouvrement) comme
    // filet de secours quand un client n'a pas d'historique de paiement suffisant, et ajoute la
    // decomposition hebdomadaire KNOWN/EXPECTED/PREDICTED, la confiance et les evenements de
    // liquidite demandes par la mission.
    // =========================================================================================

    @Transactional(readOnly = true)
    public CashForecastDto genererPrevision13Semaines(UUID entrepriseId, LocalDate dateDebut, Integer nombreSemaines,
                                                        BigDecimal seuilTresorerieMinimum, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return calculerPrevisionHebdomadaire(entrepriseId, dateDebut, nombreSemaines, seuilTresorerieMinimum);
    }

    /** Variante sans principal humain (mission P2, appel serveur-a-serveur ai-service) - meme
     * discipline que CashPositionService.genererSysteme/FinancialSnapshotService.genererSysteme
     * (mission P1 CFO). */
    @Transactional(readOnly = true)
    public CashForecastDto genererPrevision13SemainesSysteme(UUID entrepriseId, LocalDate dateDebut, Integer nombreSemaines,
                                                               BigDecimal seuilTresorerieMinimum) {
        if (!entrepriseRepository.existsById(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entreprise introuvable");
        }
        return calculerPrevisionHebdomadaire(entrepriseId, dateDebut, nombreSemaines, seuilTresorerieMinimum);
    }

    private CashForecastDto calculerPrevisionHebdomadaire(UUID entrepriseId, LocalDate dateDebut, Integer nombreSemainesDemande,
                                                            BigDecimal seuilTresorerieMinimum) {
        LocalDate asOf = dateDebut != null ? dateDebut : LocalDate.now();
        int nombreSemaines = nombreSemainesDemande != null && nombreSemainesDemande > 0
                ? Math.min(nombreSemainesDemande, SEMAINES_MAX)
                : SEMAINES_PAR_DEFAUT;
        LocalDate finHorizon = asOf.plusWeeks(nombreSemaines).minusDays(1);
        String devise = entrepriseRepository.findById(entrepriseId).map(Entreprise::getDevise).orElse("XOF");

        List<String> dataQualityWarnings = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        // Limite structurelle, toujours vraie (aucune entreprise n'a de date d'echeance
        // fournisseur persistee) - jamais conditionnelle, jamais masquee (section 12/17 du plan).
        limitations.add("supplier_due_dates_unavailable: FactureFournisseur ne porte aucune colonne d'echeance, "
                + "aucun known/expected outflow fournisseur n'est projete ici.");
        limitations.add("predicted_sales_not_projected: seules les charges recurrentes (classe 6) sont "
                + "projetees en PREDICTED - aucune baseline de ventes futures n'est estimee dans cette version.");

        // --- Connus (KNOWN) ---
        BigDecimal soldeOuvertureGlobal = ligneEcritureRepository.soldeTresorerie(entrepriseId, asOf.minusDays(1));

        List<com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte> facturesAEcheanceFuture =
                statutPaiementFactureService.ouvertes(entrepriseId, factureRepository
                        .findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                                entrepriseId, TypeFacture.FACTURE, StatutFacture.VALIDEE, asOf, finHorizon));

        List<DeclarationFiscale> declarationsDues = declarationFiscaleRepository
                .findByEntrepriseIdAndDateEcheanceBetween(entrepriseId, asOf, finHorizon);

        // --- Attendus (EXPECTED) : factures deja en retard, date de collecte estimee ---
        List<com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte> facturesEnRetard =
                statutPaiementFactureService.ouvertes(entrepriseId, factureRepository
                        .findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                                entrepriseId, TypeFacture.FACTURE, StatutFacture.VALIDEE, asOf));
        Map<UUID, PaymentBehaviorDto> comportementParTiers = new HashMap<>();
        List<FluxEstime> encaissementsAttendus = new ArrayList<>();
        Map<UUID, BigDecimal> creanceARisqueParTiers = new HashMap<>();
        Map<UUID, String> nomTiersParId = new HashMap<>();
        Map<UUID, LiquiditySeverity> risqueSeveriteParTiers = new HashMap<>();
        for (com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte ouverte : facturesEnRetard) {
            Facture facture = ouverte.facture();
            Tiers tiers = facture.getTiers();
            PaymentBehaviorDto comportement = comportementParTiers.computeIfAbsent(
                    tiers.getId(), id -> paymentBehaviorService.analyser(id, tiers.getNom()));

            LocalDate dateEstimee;
            LiquiditySeverity severiteRisque;
            if (comportement.sufficientHistory()) {
                dateEstimee = facture.getDateEcheance().plusDays(comportement.medianDelayDays());
                severiteRisque = comportement.medianDelayDays() > RETARD_MOYEN_A_RISQUE_JOURS
                        ? LiquiditySeverity.MEDIUM : LiquiditySeverity.LOW;
            } else {
                dateEstimee = facture.getDateEcheance().plusDays(DELAI_PAR_DEFAUT_SANS_HISTORIQUE_JOURS);
                severiteRisque = LiquiditySeverity.MEDIUM; // absence d'historique = incertitude, jamais ignoree
            }
            if (dateEstimee.isBefore(asOf)) {
                dateEstimee = asOf; // ne projette jamais une collecte dans le passe
            }

            if (!dateEstimee.isAfter(finHorizon)) {
                encaissementsAttendus.add(new FluxEstime(dateEstimee, ouverte.reste()));
            } else {
                dataQualityWarnings.add("receivable_beyond_horizon: creance de " + tiers.getNom()
                        + " estimee au-dela des " + nombreSemaines + " semaines de l'horizon, non projetee.");
            }

            creanceARisqueParTiers.merge(tiers.getId(), ouverte.reste(), BigDecimal::add);
            nomTiersParId.put(tiers.getId(), tiers.getNom());
            risqueSeveriteParTiers.merge(tiers.getId(), severiteRisque,
                    (a, b) -> a == LiquiditySeverity.MEDIUM || b == LiquiditySeverity.MEDIUM ? LiquiditySeverity.MEDIUM : a);
        }

        // --- Predits (PREDICTED) : charges recurrentes detectees sur l'historique classe 6 ---
        List<LigneEcriture> lignesChargesHistoriques = ligneEcritureRepository.rechercherLignesClasseSurPeriode(
                entrepriseId, "6%", asOf.minusMonths(MOIS_HISTORIQUE_RECURRENCE), asOf.minusDays(1));
        List<RecurringFlowDto> chargesRecurrentes = RecurringFlowDetector.detecter(lignesChargesHistoriques);
        LocalDate limiteRecurrenceActive = asOf.minusMonths(MOIS_INACTIVITE_RECURRENCE_MAX);
        List<FluxEstime> sortiesPredites = new ArrayList<>();
        int chargesProjetees = 0;
        for (RecurringFlowDto charge : chargesRecurrentes) {
            // Recurrence detectee sur l'historique mais sans occurrence recente = abonnement
            // probablement resilie : ne jamais projeter de sorties fantomes qui feraient plonger
            // artificiellement la tresorerie prevue (et declencher de faux evenements de liquidite).
            if (charge.lastOccurrenceDate().isBefore(limiteRecurrenceActive)) {
                dataQualityWarnings.add("stale_recurring_expense_ignored: charge " + charge.compteNumero()
                        + " detectee recurrente mais sans mouvement depuis le " + charge.lastOccurrenceDate()
                        + " - consideree resiliee, non projetee.");
                continue;
            }
            chargesProjetees++;
            LocalDate prochaine = charge.nextExpectedDate();
            while (!prochaine.isAfter(finHorizon)) {
                if (!prochaine.isBefore(asOf)) {
                    sortiesPredites.add(new FluxEstime(prochaine, charge.averageAmount()));
                }
                prochaine = prochaine.plusMonths(1);
            }
        }
        if (chargesProjetees == 0) {
            dataQualityWarnings.add("no_recurring_expenses_detected: aucune charge recurrente identifiee "
                    + "sur les " + MOIS_HISTORIQUE_RECURRENCE + " derniers mois (historique insuffisant ou "
                    + "aucune charge suffisamment stable/repetee).");
        }

        // --- Construction semaine par semaine ---
        List<CashForecastWeekDto> semaines = new ArrayList<>();
        BigDecimal ouverture = soldeOuvertureGlobal;
        List<BigDecimal> sortiesTotalesParSemaine = new ArrayList<>();
        for (int i = 0; i < nombreSemaines; i++) {
            LocalDate debutSemaine = asOf.plusWeeks(i);
            LocalDate finSemaine = debutSemaine.plusDays(6);

            BigDecimal encaissementsConnusSemaine = facturesAEcheanceFuture.stream()
                    .filter(f -> !f.facture().getDateEcheance().isBefore(debutSemaine) && !f.facture().getDateEcheance().isAfter(finSemaine))
                    .map(com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte::reste)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal encaissementsAttendusSemaine = sommerFluxDansSemaine(encaissementsAttendus, debutSemaine, finSemaine);
            BigDecimal encaissementsPreditsSemaine = BigDecimal.ZERO; // voir limitations : pas de baseline de ventes

            BigDecimal decaissementsConnusSemaine = declarationsDues.stream()
                    .filter(d -> !d.getDateEcheance().isBefore(debutSemaine) && !d.getDateEcheance().isAfter(finSemaine))
                    .map(DeclarationFiscale::getMontant)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal decaissementsAttendusSemaine = BigDecimal.ZERO; // voir limitations : pas d'echeance fournisseur
            BigDecimal decaissementsPreditsSemaine = sommerFluxDansSemaine(sortiesPredites, debutSemaine, finSemaine);

            BigDecimal entrees = encaissementsConnusSemaine.add(encaissementsAttendusSemaine).add(encaissementsPreditsSemaine);
            BigDecimal sorties = decaissementsConnusSemaine.add(decaissementsAttendusSemaine).add(decaissementsPreditsSemaine);
            BigDecimal net = entrees.subtract(sorties);
            BigDecimal cloture = ouverture.add(net);

            semaines.add(new CashForecastWeekDto(i, debutSemaine, finSemaine, ouverture, entrees, sorties, net, cloture,
                    encaissementsConnusSemaine, encaissementsAttendusSemaine, encaissementsPreditsSemaine,
                    decaissementsConnusSemaine, decaissementsAttendusSemaine, decaissementsPreditsSemaine));
            sortiesTotalesParSemaine.add(sorties);
            ouverture = cloture;
        }

        // --- Confiance (deterministe) ---
        LocalDate datePlusAncienne = ligneEcritureRepository.trouverDatePlusAncienneEcritureValidee(entrepriseId);
        long semainesHistorique = datePlusAncienne == null ? 0 : ChronoUnit.WEEKS.between(datePlusAncienne, asOf);
        ForecastConfidence confiance;
        if (semainesHistorique < SEMAINES_HISTORIQUE_MINIMUM) {
            confiance = ForecastConfidence.LOW;
            dataQualityWarnings.add("insufficient_history: seulement " + semainesHistorique
                    + " semaine(s) d'historique comptable valide disponible(s) (minimum recommande : "
                    + SEMAINES_HISTORIQUE_MINIMUM + ").");
        } else if (semainesHistorique < SEMAINES_HISTORIQUE_NORMALE) {
            confiance = ForecastConfidence.LOW;
        } else if (semainesHistorique < SEMAINES_HISTORIQUE_FORTE) {
            confiance = ForecastConfidence.MEDIUM;
        } else {
            confiance = ForecastConfidence.HIGH;
        }

        // --- Evenements de liquidite (deterministes) ---
        List<LiquidityEventDto> evenements = new ArrayList<>();
        BigDecimal moyenneSortiesHebdo = sortiesTotalesParSemaine.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(nombreSemaines, 1)), 2, RoundingMode.HALF_UP);

        int semainesConsecutivesSousLeSeuil = 0;
        for (CashForecastWeekDto semaine : semaines) {
            if (semaine.closingCash().signum() < 0) {
                evenements.add(new LiquidityEventDto(LiquidityEventType.NEGATIVE_CASH, LiquiditySeverity.HIGH,
                        semaine.weekIndex(), semaine.weekStart(), semaine.closingCash(),
                        "Solde de cloture negatif (" + semaine.closingCash() + ").", "CashForecastService"));
            }

            boolean sousLeSeuil = seuilTresorerieMinimum != null && semaine.closingCash().compareTo(seuilTresorerieMinimum) < 0;
            if (sousLeSeuil) {
                evenements.add(new LiquidityEventDto(LiquidityEventType.CASH_BELOW_THRESHOLD, LiquiditySeverity.MEDIUM,
                        semaine.weekIndex(), semaine.weekStart(), semaine.closingCash(),
                        "Solde projete (" + semaine.closingCash() + ") sous le seuil minimum (" + seuilTresorerieMinimum + ").",
                        "CashForecastService"));
                semainesConsecutivesSousLeSeuil++;
                if (semainesConsecutivesSousLeSeuil == 2) {
                    int semaineDebutGap = semaine.weekIndex() - 1;
                    evenements.add(new LiquidityEventDto(LiquidityEventType.LIQUIDITY_GAP, LiquiditySeverity.HIGH,
                            semaineDebutGap, semaines.get(semaineDebutGap).weekStart(), semaine.closingCash(),
                            "Tresorerie sous le seuil minimum pendant au moins deux semaines consecutives.",
                            "CashForecastService"));
                }
            } else {
                semainesConsecutivesSousLeSeuil = 0;
            }

            if (moyenneSortiesHebdo.signum() > 0
                    && semaine.outflows().compareTo(moyenneSortiesHebdo.multiply(MULTIPLE_SORTIE_EXCEPTIONNELLE)) > 0) {
                evenements.add(new LiquidityEventDto(LiquidityEventType.LARGE_UPCOMING_OUTFLOW, LiquiditySeverity.MEDIUM,
                        semaine.weekIndex(), semaine.weekStart(), semaine.outflows(),
                        "Sorties de la semaine (" + semaine.outflows() + ") superieures a "
                                + MULTIPLE_SORTIE_EXCEPTIONNELLE + "x la moyenne hebdomadaire (" + moyenneSortiesHebdo + ").",
                        "CashForecastService"));
            }
        }

        for (Map.Entry<UUID, BigDecimal> entree : creanceARisqueParTiers.entrySet()) {
            UUID tiersId = entree.getKey();
            LiquiditySeverity severite = risqueSeveriteParTiers.getOrDefault(tiersId, LiquiditySeverity.LOW);
            if (severite == LiquiditySeverity.LOW) {
                continue; // pas de signal suffisant pour justifier un evenement
            }
            evenements.add(new LiquidityEventDto(LiquidityEventType.RECEIVABLE_RISK, severite, 0, asOf, entree.getValue(),
                    "Creance en retard de " + nomTiersParId.get(tiersId) + " (" + entree.getValue()
                            + ") avec un historique de paiement insuffisant ou peu fiable.",
                    "PaymentBehaviorService"));
        }

        return new CashForecastDto(asOf, asOf, finHorizon, devise, soldeOuvertureGlobal,
                semaines.isEmpty() ? soldeOuvertureGlobal : semaines.get(semaines.size() - 1).closingCash(),
                seuilTresorerieMinimum, confiance, dataQualityWarnings, limitations, semaines, evenements);
    }

    private BigDecimal sommerFluxDansSemaine(List<FluxEstime> flux, LocalDate debutSemaine, LocalDate finSemaine) {
        return flux.stream()
                .filter(f -> !f.date().isBefore(debutSemaine) && !f.date().isAfter(finSemaine))
                .map(FluxEstime::montant)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Flux estime (date projetee + montant) - support interne a la construction des semaines,
     * jamais expose tel quel (le contrat public reste CashForecastWeekDto, agrege). */
    private record FluxEstime(LocalDate date, BigDecimal montant) {
    }
}
