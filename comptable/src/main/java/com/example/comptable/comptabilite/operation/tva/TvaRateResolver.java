package com.example.comptable.comptabilite.operation.tva;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.service.CompteSystemeResolver;
import com.example.comptable.fiscalite.regles.domain.TaxRule;
import com.example.comptable.fiscalite.regles.repository.TaxRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resout le taux de TVA applicable et le compte systeme correspondant (TVA_COLLECTEE pour une
 * vente, TVA_DEDUCTIBLE pour un achat), a partir du referentiel {@link TaxRule}. Ne calcule
 * jamais une TVA sans regle fiscale ET compte systeme tous deux resolus - voir {@link
 * TvaResultat#nonSupporte}. Le code "TVA" est la convention utilisee pour distinguer les regles de
 * type TVA au sein de TaxRule (qui reste un referentiel fiscal generique, pas specifique a la TVA).
 */
@Service
@RequiredArgsConstructor
public class TvaRateResolver {

    public static final String CODE_TVA = "TVA";

    /** Regles dont la source n'est pas une reference reglementaire verifiee : le resultat porte
     * alors un avertissement explicite, jamais presente comme une couverture fiscale certifiee. */
    private static final String SOURCE_DEMO_PREFIX = "FIXTURE_DEMO";

    private final TaxRuleRepository taxRuleRepository;
    private final CompteSystemeResolver compteSystemeResolver;

    public TvaResultat resoudre(UUID entrepriseId, String pays, LocalDate date, BigDecimal montantHt,
                                RoleCompteSysteme roleCompteTva) {
        Optional<TaxRule> regle = taxRuleRepository.findByPaysAndCode(pays, CODE_TVA).stream()
                .filter(r -> !r.getEffectiveFrom().isAfter(date))
                .filter(r -> r.getEffectiveTo() == null || !r.getEffectiveTo().isBefore(date))
                .filter(r -> r.getTaux() != null)
                .max(Comparator.comparing(TaxRule::getEffectiveFrom));

        if (regle.isEmpty()) {
            return TvaResultat.nonSupporte(
                    "Aucune regle TVA applicable pour le pays " + pays + " a la date " + date);
        }

        Optional<CompteComptable> compte = compteSystemeResolver.resoudre(entrepriseId, roleCompteTva);
        if (compte.isEmpty()) {
            return TvaResultat.nonSupporte(
                    "Compte systeme " + roleCompteTva + " non configure pour cette entreprise");
        }

        return calculer(regle.get(), montantHt, compte.get());
    }

    /** Variante ou l'appelant a explicitement choisi une TaxRule (ex. {@code tauxTvaId} fourni
     * dans une requete) plutot que de laisser resoudre automatiquement la regle en vigueur a la
     * date. Le compte systeme est tout de meme resolu via CompteSystemeResolver - jamais devine. */
    public TvaResultat resoudreParRegle(UUID entrepriseId, UUID taxRuleId, BigDecimal montantHt,
                                        RoleCompteSysteme roleCompteTva) {
        Optional<TaxRule> regle = taxRuleRepository.findById(taxRuleId).filter(r -> r.getTaux() != null);
        if (regle.isEmpty()) {
            return TvaResultat.nonSupporte("Regle fiscale " + taxRuleId + " introuvable ou sans taux");
        }
        Optional<CompteComptable> compte = compteSystemeResolver.resoudre(entrepriseId, roleCompteTva);
        if (compte.isEmpty()) {
            return TvaResultat.nonSupporte(
                    "Compte systeme " + roleCompteTva + " non configure pour cette entreprise");
        }
        return calculer(regle.get(), montantHt, compte.get());
    }

    private TvaResultat calculer(TaxRule taxRule, BigDecimal montantHt, CompteComptable compte) {
        BigDecimal montantTva = montantHt.multiply(taxRule.getTaux())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        boolean sourceDemo = taxRule.getSource() != null && taxRule.getSource().startsWith(SOURCE_DEMO_PREFIX);

        return new TvaResultat(taxRule.getTaux(), montantTva, compte, taxRule.getId(), false, null, sourceDemo);
    }
}
