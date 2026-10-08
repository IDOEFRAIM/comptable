package com.example.comptable.fiscalite.core;

import java.math.BigDecimal;
import java.util.List;

/**
 * Contenu d'un cadre fiscal TVA. TOUT ce qui est réglementaire est ici et nulle part ailleurs : taux (code, libellé, pourcentage), périodicité, règle d'échéance, tolérance d'arrondi, lignes de la
 * déclaration (codes officiels). Le code ne contient aucune de ces valeurs ; un cadre DRAFT ne produit jamais de déclaration FINAL.
 *
 * @param rates                  taux applicables (pourcentage, ex. « 10 » pour 10 %) fournis par la source vérifiée
 * @param periodicity            {@code MONTHLY} ou {@code QUARTERLY} (indicatif : la période est choisie par l'utilisateur)
 * @param due                    règle d'échéance : jours après la fin de période (mois + jour du mois) ; absente → aucune échéance générée
 * @param toleranceAmount        écart absolu toléré entre TVA comptabilisée et TVA calculée (arrondis), dans la devise de l'entreprise
 * @param rateMatchTolerancePoints écart toléré, en points de pourcentage, entre le taux implicite d'une opération et un taux du cadre
 * @param lines                  lignes de la déclaration ; absentes → structure générique interne, explicitement non officielle
 * @param carryForward           le cadre vérifié prévoit le report d'un crédit de TVA ; {@code null} ou faux → aucun report, aucun crédit d'ouverture validé (état à revoir)
 */
public record VatRules(List<Rate> rates, String periodicity, DueRule due, BigDecimal toleranceAmount, BigDecimal rateMatchTolerancePoints, List<DeclarationLine> lines, Boolean carryForward) {

    public record Rate(String code, String label, BigDecimal percent) {
    }

    public record DueRule(int monthsAfterPeriodEnd, int dayOfMonth) {
    }

    /** {@code source} : OUTPUT_BASE, OUTPUT_VAT, INPUT_BASE, INPUT_VAT_DEDUCTIBLE, ADJUSTMENTS_OUTPUT, ADJUSTMENTS_INPUT, NET_VAT. */
    public record DeclarationLine(String code, String label, String source) {
    }

    /** Constructeur sans règle de report (aucun report prévu par le cadre). */
    public VatRules(List<Rate> rates, String periodicity, DueRule due, BigDecimal toleranceAmount, BigDecimal rateMatchTolerancePoints, List<DeclarationLine> lines) {
        this(rates, periodicity, due, toleranceAmount, rateMatchTolerancePoints, lines, null);
    }

    /** Le report d'un crédit n'est actif que si le cadre vérifié le prévoit explicitement. */
    public boolean reportPrevu() {
        return Boolean.TRUE.equals(carryForward);
    }

    public VatRules {
        rates = rates == null ? List.of() : List.copyOf(rates);
        lines = lines == null ? List.of() : List.copyOf(lines);
        toleranceAmount = toleranceAmount == null ? BigDecimal.ZERO : toleranceAmount;
        rateMatchTolerancePoints = rateMatchTolerancePoints == null ? new BigDecimal("0.5") : rateMatchTolerancePoints;
    }
}
