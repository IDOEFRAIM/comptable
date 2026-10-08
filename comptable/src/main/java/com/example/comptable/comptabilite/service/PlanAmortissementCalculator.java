package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.ModeAmortissement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Calcul pur du plan d'amortissement d'une immobilisation, sans dependance Spring/DB. Le lineaire applique le prorata temporis SYSCOHADA sur l'annee
 * d'acquisition (mois d'acquisition compte comme mois entier) : quand l'annee 1 n'est que
 * partielle, l'echeancier compte dureeAnnees+1 lignes, la derniere absorbant le reliquat non
 * consomme en annee 1 - jamais de dotation manquante, le bien reste amorti a 100%. Le degressif
 * reste volontairement hors de ce prorata (non demande) : il utilise le coefficient fiscal usuel
 * (1.25 / 1.75 / 2.25 selon la duree) sans bascule automatique vers le lineaire en fin de plan,
 * la derniere annee absorbant simplement la valeur nette comptable residuelle.
 */
public final class PlanAmortissementCalculator {

    private PlanAmortissementCalculator() {
    }

    public record LigneAmortissement(int annee, BigDecimal dotation, BigDecimal valeurNetteComptableFin) {
    }

    public static List<LigneAmortissement> calculer(BigDecimal valeurOrigine, LocalDate dateAcquisition,
                                                      int dureeAnnees, ModeAmortissement mode) {
        return mode == ModeAmortissement.DEGRESSIF
                ? calculerDegressif(valeurOrigine, dateAcquisition, dureeAnnees)
                : calculerLineaire(valeurOrigine, dateAcquisition, dureeAnnees);
    }

    /**
     * Annee 1 au prorata temporis : {@code Dotation An 1 = Valeur d'origine x (1 / Duree) x
     * (Mois restants dans l'annee / 12)}, mois d'acquisition inclus (acquisition en mars = 10
     * mois restants, pas 9). Si l'acquisition tombe un 1er janvier, mois restants = 12 : aucun
     * prorata, comportement identique a un plan sans prorata. Sinon, la fraction d'annee non
     * amortie en annee 1 est reportee sur une {@code dureeAnnees}+1-ieme ligne en fin de tableau,
     * qui absorbe aussi les ecarts d'arrondi pour retomber pile a zero.
     */
    private static List<LigneAmortissement> calculerLineaire(BigDecimal valeurOrigine, LocalDate dateAcquisition, int dureeAnnees) {
        BigDecimal dotationAnnuelle = valeurOrigine.divide(BigDecimal.valueOf(dureeAnnees), 2, RoundingMode.HALF_UP);
        int moisRestants = 13 - dateAcquisition.getMonthValue();
        BigDecimal dotationAn1 = dotationAnnuelle.multiply(BigDecimal.valueOf(moisRestants))
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        boolean prorata = moisRestants < 12;
        int nombreLignes = prorata ? dureeAnnees + 1 : dureeAnnees;

        List<LigneAmortissement> lignes = new ArrayList<>();
        BigDecimal cumule = BigDecimal.ZERO;
        int anneeDebut = dateAcquisition.getYear();
        for (int i = 0; i < nombreLignes; i++) {
            boolean derniere = i == nombreLignes - 1;
            BigDecimal dotation;
            if (i == 0) {
                dotation = dotationAn1;
            } else if (derniere) {
                dotation = valeurOrigine.subtract(cumule);
            } else {
                dotation = dotationAnnuelle;
            }
            cumule = cumule.add(dotation);
            lignes.add(new LigneAmortissement(anneeDebut + i, dotation, valeurOrigine.subtract(cumule)));
        }
        return lignes;
    }

    private static List<LigneAmortissement> calculerDegressif(BigDecimal valeurOrigine, LocalDate dateAcquisition, int dureeAnnees) {
        List<LigneAmortissement> lignes = new ArrayList<>();
        BigDecimal taux = coefficientDegressif(dureeAnnees).divide(BigDecimal.valueOf(dureeAnnees), 4, RoundingMode.HALF_UP);
        BigDecimal valeurNetteComptable = valeurOrigine;
        int anneeDebut = dateAcquisition.getYear();
        for (int i = 0; i < dureeAnnees; i++) {
            boolean derniere = i == dureeAnnees - 1;
            BigDecimal dotation = derniere ? valeurNetteComptable : valeurNetteComptable.multiply(taux).setScale(2, RoundingMode.HALF_UP);
            valeurNetteComptable = valeurNetteComptable.subtract(dotation);
            lignes.add(new LigneAmortissement(anneeDebut + i, dotation, valeurNetteComptable));
        }
        return lignes;
    }

    private static BigDecimal coefficientDegressif(int dureeAnnees) {
        if (dureeAnnees <= 4) {
            return new BigDecimal("1.25");
        }
        if (dureeAnnees <= 6) {
            return new BigDecimal("1.75");
        }
        return new BigDecimal("2.25");
    }
}
