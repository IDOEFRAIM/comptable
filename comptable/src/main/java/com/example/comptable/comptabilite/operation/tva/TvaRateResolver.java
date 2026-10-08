package com.example.comptable.comptabilite.operation.tva;

import com.example.comptable.fiscalite.regles.domain.TaxRule;
import com.example.comptable.fiscalite.regles.repository.TaxRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Resout le TAUX de TVA applicable et le montant de TVA correspondant, a partir du referentiel
 * {@link TaxRule} (fiscalite). Ne calcule jamais une TVA sans regle fiscale. Ne s'occupe PAS du compte
 * comptable : celui-ci est resolu par {@code AccountingAccountResolver} (purposes TVA_COLLECTEE /
 * TVA_RECUPERABLE). Le code "TVA" est la convention utilisee pour distinguer les regles de type TVA
 * au sein de TaxRule (qui reste un referentiel fiscal generique, pas specifique a la TVA).
 */
@Service
@RequiredArgsConstructor
public class TvaRateResolver {

    public static final String CODE_TVA = "TVA";

    /** Regles dont la source n'est pas une reference reglementaire verifiee : le resultat porte
     * alors un avertissement explicite, jamais presente comme une couverture fiscale certifiee. */
    private static final String SOURCE_DEMO_PREFIX = "FIXTURE_DEMO";

    private final TaxRuleRepository taxRuleRepository;

    /** Regle TVA en vigueur dans {@code pays} a {@code date}. */
    public TvaResultat resoudre(String pays, LocalDate date, BigDecimal montantHt) {
        Optional<TaxRule> regle = taxRuleRepository.findByPaysAndCode(pays, CODE_TVA).stream()
                .filter(r -> !r.getEffectiveFrom().isAfter(date))
                .filter(r -> r.getEffectiveTo() == null || !r.getEffectiveTo().isBefore(date))
                .filter(r -> r.getTaux() != null)
                .max(Comparator.comparing(TaxRule::getEffectiveFrom));

        if (regle.isEmpty()) {
            return TvaResultat.nonSupporte("Aucune regle TVA applicable pour le pays " + pays + " a la date " + date);
        }
        return calculer(regle.get(), montantHt);
    }

    /** Variante ou l'appelant a explicitement choisi une TaxRule (ex. {@code tauxTvaId} fourni dans une
     * requete) plutot que de laisser resoudre automatiquement la regle en vigueur a la date. */
    public TvaResultat resoudreParRegle(UUID taxRuleId, BigDecimal montantHt) {
        Optional<TaxRule> regle = taxRuleRepository.findById(taxRuleId).filter(r -> r.getTaux() != null);
        if (regle.isEmpty()) {
            return TvaResultat.nonSupporte("Regle fiscale " + taxRuleId + " introuvable ou sans taux");
        }
        return calculer(regle.get(), montantHt);
    }

    private TvaResultat calculer(TaxRule taxRule, BigDecimal montantHt) {
        BigDecimal montantTva = montantHt.multiply(taxRule.getTaux())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        boolean sourceDemo = taxRule.getSource() != null && taxRule.getSource().startsWith(SOURCE_DEMO_PREFIX);
        return new TvaResultat(taxRule.getTaux(), montantTva, taxRule.getId(), false, null, sourceDemo);
    }
}
