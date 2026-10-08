package com.example.comptable.fiscalite.core;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Calcul des périodes et échéances d'une année À PARTIR D'UNE RÈGLE FOURNIE par un cadre fiscal VERIFIED (périodicité + règle d'échéance). Sans règle, aucune période ni échéance n'est produite :
 * aucune convention (« le 15 du mois suivant », par exemple) n'est jamais codée ici. Logique pure, sans Spring ni base.
 */
public final class TaxDeadlineGenerator {

    public static final String MENSUELLE = "MONTHLY";

    private TaxDeadlineGenerator() {
    }

    public record PeriodeEcheance(String periode, LocalDate debut, LocalDate fin, LocalDate dateEcheance) {
    }

    /** Date limite d'une période d'après la règle du cadre ; {@code null} si le cadre ne fournit aucune règle d'échéance. */
    public static LocalDate echeance(VatRules regles, LocalDate finDePeriode) {
        if (regles == null || regles.due() == null || finDePeriode == null) {
            return null;
        }
        YearMonth mois = YearMonth.from(finDePeriode).plusMonths(regles.due().monthsAfterPeriodEnd());
        return mois.atDay(Math.min(regles.due().dayOfMonth(), mois.lengthOfMonth()));
    }

    /** Périodes mensuelles d'une année, seulement si le cadre déclare une périodicité mensuelle ET une règle d'échéance ; sinon liste vide. */
    public static List<PeriodeEcheance> periodesAnnuelles(VatRules regles, int annee) {
        List<PeriodeEcheance> periodes = new ArrayList<>();
        if (regles == null || regles.due() == null || !MENSUELLE.equalsIgnoreCase(regles.periodicity())) {
            return periodes;
        }
        for (int mois = 1; mois <= 12; mois++) {
            YearMonth ym = YearMonth.of(annee, mois);
            periodes.add(new PeriodeEcheance("%04d-%02d".formatted(annee, mois), ym.atDay(1), ym.atEndOfMonth(), echeance(regles, ym.atEndOfMonth())));
        }
        return periodes;
    }
}
