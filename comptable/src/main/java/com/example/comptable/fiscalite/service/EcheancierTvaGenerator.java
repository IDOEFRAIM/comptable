package com.example.comptable.fiscalite.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure logique de calcul des 12 echeances mensuelles de TVA d'une annee (declaration
 * et paiement le 15 du mois suivant, convention courante dans l'espace OHADA). Extrait
 * du service pour rester testable sans dependances (pas de Spring, pas de DB).
 */
public final class EcheancierTvaGenerator {

    private EcheancierTvaGenerator() {
    }

    public record PeriodeEcheance(String periode, LocalDate dateEcheance) {
    }

    public static List<PeriodeEcheance> genererPeriodesAnnuelles(int annee) {
        List<PeriodeEcheance> periodes = new ArrayList<>();
        for (int mois = 1; mois <= 12; mois++) {
            String periode = "%04d-%02d".formatted(annee, mois);
            LocalDate echeance = YearMonth.of(annee, mois).plusMonths(1).atDay(15);
            periodes.add(new PeriodeEcheance(periode, echeance));
        }
        return periodes;
    }
}
