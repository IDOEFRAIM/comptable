package com.example.comptable.finance.forecast;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.finance.forecast.web.dto.RecurringFlowDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Detection de charges recurrentes (mission P2, "13-Week Cash Forecast", section 13 du plan -
 * loyers, telecom, abonnements, assurances...) a partir de l'historique des mouvements deja
 * valides (classe 6). Baseline deterministe uniquement (section 13/35 du plan : "ne pas utiliser
 * le LLM pour cette detection", "pas de machine learning complexe") : moyenne + verification de
 * stabilite sur des occurrences MENSUELLES - jamais un modele entraine.
 * <p>
 * Limite assumee et documentee : seule la cadence mensuelle est detectee dans cette version
 * (hebdomadaire/trimestrielle non couvertes) - la tres grande majorite des charges recurrentes
 * d'une PME (loyer, telecom, assurance, abonnements) suivent deja ce rythme.
 */
public final class RecurringFlowDetector {

    /** En-dessous de ce nombre de mois distincts avec un mouvement sur le compte, aucune
     * recurrence n'est retenue (section 13 du plan : eviter de qualifier "recurrent" une charge
     * ponctuelle vue une ou deux fois par hasard). */
    public static final int OCCURRENCES_MINIMUM = 3;

    /** Stabilite des montants : le plus grand mouvement mensuel ne doit pas depasser ce multiple
     * du plus petit, sinon la charge n'est pas assez reguliere pour etre projetee telle quelle
     * (ex: un loyer a 250000 chaque mois est stable ; un compte de charges divers variant de
     * 10000 a 900000 ne l'est pas). Seuil documente, ajustable - pas un modele statistique. */
    private static final BigDecimal RATIO_STABILITE_MAXIMUM = new BigDecimal("3");

    private RecurringFlowDetector() {
    }

    public static List<RecurringFlowDto> detecter(List<LigneEcriture> lignesClasseCharges) {
        Map<CompteComptable, List<LigneEcriture>> parCompte = lignesClasseCharges.stream()
                .collect(Collectors.groupingBy(LigneEcriture::getCompte));

        List<RecurringFlowDto> resultats = new ArrayList<>();
        for (Map.Entry<CompteComptable, List<LigneEcriture>> entree : parCompte.entrySet()) {
            RecurringFlowDto detection = analyserCompte(entree.getKey(), entree.getValue());
            if (detection != null) {
                resultats.add(detection);
            }
        }
        return resultats.stream().sorted(Comparator.comparing(RecurringFlowDto::compteNumero)).toList();
    }

    private static RecurringFlowDto analyserCompte(CompteComptable compte, List<LigneEcriture> lignes) {
        Map<YearMonth, BigDecimal> montantParMois = lignes.stream()
                .collect(Collectors.groupingBy(
                        l -> YearMonth.from(l.getEcriture().getDateEcriture()),
                        Collectors.reducing(BigDecimal.ZERO,
                                l -> l.getDebit().subtract(l.getCredit()), BigDecimal::add)));

        // Un mois sans mouvement net (charge et son extourne totale, ex.) n'est pas une occurrence.
        List<BigDecimal> montantsMensuels = montantParMois.values().stream()
                .filter(m -> m.signum() > 0)
                .toList();

        if (montantsMensuels.size() < OCCURRENCES_MINIMUM) {
            return null;
        }

        BigDecimal minimum = montantsMensuels.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal maximum = montantsMensuels.stream().max(BigDecimal::compareTo).orElseThrow();
        if (minimum.signum() <= 0 || maximum.divide(minimum, 4, java.math.RoundingMode.HALF_UP)
                .compareTo(RATIO_STABILITE_MAXIMUM) > 0) {
            return null; // montants trop instables pour etre qualifies de recurrents.
        }

        BigDecimal moyenne = montantsMensuels.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(montantsMensuels.size()), 0, java.math.RoundingMode.HALF_UP);

        LocalDate derniereOccurrence = lignes.stream()
                .map(l -> l.getEcriture().getDateEcriture())
                .max(LocalDate::compareTo)
                .orElseThrow();

        return new RecurringFlowDto(
                compte.getId(), compte.getNumero(), compte.getIntitule(),
                moyenne, montantsMensuels.size(), derniereOccurrence, derniereOccurrence.plusMonths(1));
    }
}
