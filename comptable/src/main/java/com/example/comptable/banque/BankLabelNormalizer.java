package com.example.comptable.banque;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisation et extraction d'indices sur un libelle bancaire. Fonction pure.
 *
 * <p>Le libelle original n'est JAMAIS modifie ni perdu : la version normalisee ne sert qu'a comparer. Les indices extraits (nom probable du
 * tiers, numero de facture probable, reference, moyen de paiement) sont des INDICES pour le matching, jamais des verites.</p>
 */
public final class BankLabelNormalizer {

    /** Indices lus dans un libelle ; chaque champ peut etre {@code null}. */
    public record Indices(String nomTiers, String numeroFacture, String reference, String moyenPaiement) {
    }

    private static final Pattern DIACRITIQUES = Pattern.compile("\\p{M}+");
    private static final Pattern PONCTUATION = Pattern.compile("[^A-Z0-9/\\- ]");
    private static final Pattern ESPACES = Pattern.compile("\\s+");
    private static final Pattern FACTURE = Pattern.compile("\\b((?:FACTURE|FACT|FAC|FA|INV)[ .:#-]*[0-9][A-Z0-9/-]*)");
    private static final Pattern REFERENCE = Pattern.compile("\\bREF(?:ERENCE)?[ .:#-]*([A-Z0-9][A-Z0-9/-]{3,})");
    private static final Pattern NUMERO = Pattern.compile("\\bN[O0]?[ .:]+([0-9]{5,})");
    private static final Pattern TIERS = Pattern.compile("(?:VIR(?:EMENT)?(?: RECU| EMIS| SEPA| PERMANENT)?|PAIEMENT|REGLEMENT|PRLV|CHQ|CHEQUE)"
            + " +(?:DE |DU |D |A |AU |EN FAVEUR DE |POUR |ORDRE )?([A-Z][A-Z &-]{2,}?)(?= +(?:REF|FACT|FAC|FA|INV|N |NO |[0-9])|$)");

    private BankLabelNormalizer() {
    }

    /** Majuscules sans accents, ponctuation inutile et espaces multiples supprimes (les tirets et barres des numeros sont conserves). */
    public static String normaliser(String libelle) {
        if (libelle == null) {
            return "";
        }
        String sansAccents = DIACRITIQUES.matcher(Normalizer.normalize(libelle, Normalizer.Form.NFD)).replaceAll("");
        String majuscules = sansAccents.toUpperCase(java.util.Locale.ROOT);
        String sansPonctuation = PONCTUATION.matcher(majuscules).replaceAll(" ");
        return ESPACES.matcher(sansPonctuation).replaceAll(" ").trim();
    }

    public static Indices extraire(String libelle) {
        String n = normaliser(libelle);
        return new Indices(nomTiers(n), premier(FACTURE, n, 1), referenceDe(n), moyenPaiement(n));
    }

    private static String nomTiers(String n) {
        Matcher m = TIERS.matcher(n);
        if (!m.find()) {
            return null;
        }
        String nom = m.group(1).trim();
        return nom.length() >= 3 ? nom : null;
    }

    private static String referenceDe(String n) {
        String ref = premier(REFERENCE, n, 1);
        return ref != null ? ref : premier(NUMERO, n, 1);
    }

    private static String premier(Pattern p, String texte, int groupe) {
        Matcher m = p.matcher(texte);
        return m.find() ? m.group(groupe).trim() : null;
    }

    private static final java.util.List<String> MOTS_VIREMENT_INTERNE = java.util.List.of("VIREMENT INTERNE", "VIR INTERNE", "ENTRE COMPTES", "ENTRE MES COMPTES",
            "TRANSFERT", "VIREMENT DE FONDS", "VIR DE FONDS", "COMPTE A COMPTE");

    /** Le libelle annonce-t-il un virement entre deux comptes de l'entreprise ? (indice, jamais une verite) */
    public static boolean evoqueUnVirementInterne(String libelle) {
        String n = normaliser(libelle);
        return MOTS_VIREMENT_INTERNE.stream().anyMatch(n::contains);
    }

    static String moyenPaiement(String n) {
        if (MOTS_VIREMENT_INTERNE.stream().anyMatch(n::contains)) {
            return "VIREMENT_INTERNE";
        }
        if (contient(n, "MOBILE MONEY", "ORANGE MONEY", "MTN MOMO", "MOOV MONEY", "WAVE")) {
            return "MOBILE_MONEY";
        }
        if (contient(n, "FRAIS", "COMMISSION", "AGIOS", "TENUE DE COMPTE")) {
            return "FRAIS";
        }
        if (contient(n, "RETRAIT", "DAB", "GAB")) {
            return "RETRAIT_ESPECES";
        }
        if (contient(n, "VERSEMENT ESPECES", "DEPOT ESPECES", "VERSEMENT")) {
            return "VERSEMENT_ESPECES";
        }
        if (contient(n, "PRLV", "PRELEVEMENT")) {
            return "PRELEVEMENT";
        }
        if (contient(n, "CHQ", "CHEQUE")) {
            return "CHEQUE";
        }
        if (contient(n, "CARTE", "CB")) {
            return "CARTE";
        }
        if (contient(n, "VIR", "VIREMENT")) {
            return "VIREMENT";
        }
        return null;
    }

    private static boolean contient(String n, String... mots) {
        for (String mot : mots) {
            if (Pattern.compile("(?:^| )" + Pattern.quote(mot) + "(?: |$)").matcher(n).find()) {
                return true;
            }
        }
        return false;
    }
}
