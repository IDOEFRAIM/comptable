package com.example.comptable.banque;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Correspondance colonnes du CSV -&gt; champs d'une transaction. Les colonnes sont des POSITIONS (0, 1, 2...) ; {@code null} = colonne absente.
 * Soit {@code montant} (signe), soit {@code debit} et/ou {@code credit}. Sans configuration explicite, la correspondance est deduite de
 * l'en-tete (francais ou anglais) ; a defaut, le format historique {@code date;libelle;montant} est applique.
 *
 * @param separateur {@code ;}, {@code ,} ou tabulation ; {@code null} = detecte
 */
public record BankCsvMapping(Integer date, Integer dateValeur, Integer libelle, Integer reference, Integer debit, Integer credit,
                             Integer montant, Integer solde, boolean enTete, String separateur) {

    private static final Pattern DIACRITIQUES = Pattern.compile("\\p{M}+");

    /** Format historique : {@code date;libelle;montant}, premiere ligne = en-tete eventuel. */
    public static BankCsvMapping historique() {
        return new BankCsvMapping(0, null, 1, null, null, null, 2, null, true, null);
    }

    public boolean aUnMontant() {
        return montant != null || debit != null || credit != null;
    }

    /** Deduit le mapping des noms de colonnes ; {@code null} si l'en-tete ne permet pas d'identifier date, libelle et montant. */
    public static BankCsvMapping deduireDeLEnTete(List<String> colonnes, String separateur) {
        Integer date = null;
        Integer dateValeur = null;
        Integer libelle = null;
        Integer reference = null;
        Integer debit = null;
        Integer credit = null;
        Integer montant = null;
        Integer solde = null;
        for (int i = 0; i < colonnes.size(); i++) {
            String c = nom(colonnes.get(i));
            if (dateValeur == null && (c.contains("valeur") || c.equals("value date"))) {
                dateValeur = i;
            } else if (date == null && (c.equals("date") || c.startsWith("date op") || c.startsWith("date compt") || c.equals("booking date")
                    || c.equals("dateop"))) {
                date = i;
            } else if (libelle == null && (c.startsWith("libelle") || c.equals("description") || c.equals("label") || c.startsWith("designation")
                    || c.startsWith("detail") || c.startsWith("intitule") || c.equals("operation"))) {
                libelle = i;
            } else if (reference == null && (c.startsWith("ref") || c.equals("numero") || c.startsWith("n op"))) {
                reference = i;
            } else if (debit == null && (c.startsWith("debit") || c.equals("sortie") || c.equals("retrait") || c.equals("withdrawal"))) {
                debit = i;
            } else if (credit == null && (c.startsWith("credit") || c.equals("entree") || c.equals("encaissement") || c.equals("deposit"))) {
                credit = i;
            } else if (solde == null && (c.startsWith("solde") || c.equals("balance"))) {
                solde = i;
            } else if (montant == null && (c.startsWith("montant") || c.equals("amount") || c.equals("mouvement"))) {
                montant = i;
            }
        }
        if (date == null || libelle == null || (montant == null && debit == null && credit == null)) {
            return null;
        }
        return new BankCsvMapping(date, dateValeur, libelle, reference, debit, credit, montant, solde, true, separateur);
    }

    private static String nom(String colonne) {
        String n = DIACRITIQUES.matcher(Normalizer.normalize(colonne == null ? "" : colonne, Normalizer.Form.NFD)).replaceAll("");
        return n.toLowerCase(Locale.ROOT).replace("_", " ").replace("\"", "").trim();
    }
}
