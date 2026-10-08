package com.example.comptable.etatsfinanciers.definition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Evaluateur des formules des definitions : {@code CODE}, {@code +}, {@code -}, parentheses, {@code SUM(DEBUT..FIN)} (somme des lignes dont l'ordre est compris
 * entre celui de DEBUT et celui de FIN, bornes incluses). Tout en {@link BigDecimal}. Une valeur inconnue ({@code null}) rend le resultat inconnu : on ne
 * remplace jamais une donnee absente par zero.
 */
public final class FormulaEvaluator {

    private FormulaEvaluator() {
    }

    /** Codes de lignes references par une formule (bornes de SUM incluses). */
    public static Set<String> codesReferences(String formule) {
        Set<String> codes = new LinkedHashSet<>();
        for (String jeton : jetons(formule)) {
            if (jeton.matches("[A-Z]{2}")) {
                codes.add(jeton);
            }
        }
        return codes;
    }

    /**
     * @param valeurs valeur d'une ligne par code (peut etre null)
     * @param plage   lignes comprises entre deux codes (dans l'ordre de la definition)
     */
    public static BigDecimal evaluer(String formule, Function<String, BigDecimal> valeurs, java.util.function.BiFunction<String, String, List<String>> plage) {
        Analyseur analyseur = new Analyseur(jetons(formule), valeurs, plage);
        BigDecimal resultat = analyseur.expression();
        if (analyseur.position != analyseur.jetons.size()) {
            throw new IllegalArgumentException("Formule invalide : " + formule);
        }
        return resultat;
    }

    private static List<String> jetons(String formule) {
        List<String> jetons = new ArrayList<>();
        int i = 0;
        while (i < formule.length()) {
            char c = formule.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '+' || c == '-' || c == '(' || c == ')') {
                jetons.add(String.valueOf(c));
                i++;
            } else if (formule.startsWith("..", i)) {
                jetons.add("..");
                i += 2;
            } else if (formule.startsWith("SUM", i)) {
                jetons.add("SUM");
                i += 3;
            } else if (Character.isUpperCase(c)) {
                int fin = i;
                while (fin < formule.length() && Character.isUpperCase(formule.charAt(fin))) {
                    fin++;
                }
                jetons.add(formule.substring(i, fin));
                i = fin;
            } else {
                throw new IllegalArgumentException("Caractere inattendu dans la formule : " + formule);
            }
        }
        return jetons;
    }

    private static final class Analyseur {
        private final List<String> jetons;
        private final Function<String, BigDecimal> valeurs;
        private final java.util.function.BiFunction<String, String, List<String>> plage;
        private int position;

        Analyseur(List<String> jetons, Function<String, BigDecimal> valeurs, java.util.function.BiFunction<String, String, List<String>> plage) {
            this.jetons = jetons;
            this.valeurs = valeurs;
            this.plage = plage;
        }

        BigDecimal expression() {
            BigDecimal total = terme();
            while (position < jetons.size() && (jetons.get(position).equals("+") || jetons.get(position).equals("-"))) {
                boolean plus = jetons.get(position++).equals("+");
                BigDecimal suivant = terme();
                total = total == null || suivant == null ? null : plus ? total.add(suivant) : total.subtract(suivant);
            }
            return total;
        }

        private BigDecimal terme() {
            String jeton = jetons.get(position++);
            if (jeton.equals("(")) {
                BigDecimal valeur = expression();
                position++;   // ')'
                return valeur;
            }
            if (jeton.equals("-")) {
                BigDecimal valeur = terme();
                return valeur == null ? null : valeur.negate();
            }
            if (jeton.equals("SUM")) {
                position++;   // '('
                String debut = jetons.get(position++);
                position++;   // '..'
                String fin = jetons.get(position++);
                position++;   // ')'
                BigDecimal somme = BigDecimal.ZERO;
                for (String code : plage.apply(debut, fin)) {
                    BigDecimal v = valeurs.apply(code);
                    if (v == null) {
                        return null;
                    }
                    somme = somme.add(v);
                }
                return somme;
            }
            return valeurs.apply(jeton);
        }
    }

    /**
     * Evalue toutes les lignes d'une definition : les lignes alimentees (MAPPED, METHOD, BALANCE_DERIVED, RESULT_IDENTITY, STRUCTURE_ONLY) viennent de {@code alimentees} ; les lignes
     * FORMULA sont evaluees recursivement (une formule peut referencer une ligne placee apres elle dans le modele : un total d'en-tete, par exemple ; les cycles sont refuses a la
     * validation). Une valeur absente ({@code null}) rend le resultat inconnu.
     */
    public static Map<String, BigDecimal> evaluerDefinition(FinancialStatementDefinition definition, Map<String, BigDecimal> alimentees) {
        List<StatementLineDefinition> ordonnees = definition.lines().stream().sorted(java.util.Comparator.comparingInt(StatementLineDefinition::order)).toList();
        Map<String, StatementLineDefinition> parCode = new java.util.HashMap<>();
        ordonnees.forEach(l -> parCode.put(l.code(), l));
        Map<String, BigDecimal> valeurs = new java.util.LinkedHashMap<>();
        java.util.Set<String> faits = new java.util.HashSet<>();
        for (StatementLineDefinition ligne : ordonnees) {
            evaluerLigne(ligne.code(), parCode, alimentees, valeurs, faits, new java.util.HashSet<>(), ordonnees);
        }
        return valeurs;
    }

    private static BigDecimal evaluerLigne(String code, Map<String, StatementLineDefinition> parCode, Map<String, BigDecimal> alimentees, Map<String, BigDecimal> valeurs,
                                           java.util.Set<String> faits, java.util.Set<String> enCours, List<StatementLineDefinition> ordonnees) {
        if (faits.contains(code)) {
            return valeurs.get(code);
        }
        if (!enCours.add(code)) {
            throw new IllegalStateException("Cycle de formules sur la ligne " + code);
        }
        StatementLineDefinition ligne = parCode.get(code);
        BigDecimal v;
        if (ligne.calculationType() == CalculationType.FORMULA) {
            v = evaluer(ligne.formula(), c -> evaluerLigne(c, parCode, alimentees, valeurs, faits, enCours, ordonnees), (x, y) -> codesEntre(ordonnees, x, y));
        } else {
            v = alimentees.get(code);
        }
        enCours.remove(code);
        valeurs.put(code, v);
        faits.add(code);
        return v;
    }

    private static List<String> codesEntre(List<StatementLineDefinition> ordonnees, String debut, String fin) {
        int ordreDebut = ordonnees.stream().filter(l -> l.code().equals(debut)).findFirst().orElseThrow().order();
        int ordreFin = ordonnees.stream().filter(l -> l.code().equals(fin)).findFirst().orElseThrow().order();
        return ordonnees.stream().filter(l -> l.order() >= ordreDebut && l.order() <= ordreFin).map(StatementLineDefinition::code).toList();
    }
}
