package com.example.comptable.comptabilite.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Montant en toutes lettres, en français (« Arrêtée la présente facture à la somme de… »), mention usuelle d'une facture.
 * Règles du français respectées : « vingt et un », « soixante et onze », « quatre-vingts » mais « quatre-vingt-un », « deux cents » mais « deux cent un », « mille » invariable,
 * « million(s) » et « milliard(s) » accordés (et « cent » / « vingt » prennent un s devant eux).
 */
public final class MontantEnLettres {

    private static final String[] UNITES = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept",
            "dix-huit", "dix-neuf"};
    private static final String[] DIZAINES = {"", "", "vingt", "trente", "quarante", "cinquante", "soixante"};

    private MontantEnLettres() {
    }

    /** Partie entière en lettres, avec la première lettre en majuscule. Au-delà de 999 999 999 999, renvoie le nombre en chiffres (cas qui n'existe pas en pratique). */
    public static String enLettres(long n) {
        if (n < 0) {
            return "moins " + enLettres(-n);
        }
        if (n == 0) {
            return "Zéro";
        }
        if (n > 999_999_999_999L) {
            return String.valueOf(n);
        }
        long milliards = n / 1_000_000_000L;
        long millions = (n / 1_000_000L) % 1000;
        long milliers = (n / 1000L) % 1000;
        long reste = n % 1000;
        StringBuilder sb = new StringBuilder();
        if (milliards > 0) {
            sb.append(milliards == 1 ? "un" : sous1000(milliards, true)).append(" milliard").append(milliards > 1 ? "s" : "");
        }
        if (millions > 0) {
            ajouter(sb, (millions == 1 ? "un" : sous1000(millions, true)) + " million" + (millions > 1 ? "s" : ""));
        }
        if (milliers > 0) {
            ajouter(sb, milliers == 1 ? "mille" : sous1000(milliers, false) + " mille");
        }
        if (reste > 0) {
            ajouter(sb, sous1000(reste, true));
        }
        String texte = sb.toString();
        return Character.toUpperCase(texte.charAt(0)) + texte.substring(1);
    }

    /**
     * « Cent mille francs CFA », « mille deux cent cinquante-quatre euros et trente centimes ». Le franc CFA n'a pas de décimales : le montant est arrondi à l'unité.
     */
    public static String montant(BigDecimal montant, String devise) {
        boolean cfa = devise == null || devise.equals("XOF") || devise.equals("XAF");
        BigDecimal arrondi = montant.setScale(cfa ? 0 : 2, RoundingMode.HALF_UP).abs();
        long entier = arrondi.toBigInteger().longValueExact();
        String texte = enLettres(entier) + " " + libelleDevise(devise, entier);
        if (!cfa) {
            long centimes = arrondi.remainder(BigDecimal.ONE).movePointRight(2).longValue();
            if (centimes > 0) {
                texte += " et " + enLettres(centimes).toLowerCase() + " centime" + (centimes > 1 ? "s" : "");
            }
        }
        return texte;
    }

    private static String libelleDevise(String devise, long entier) {
        String d = devise == null ? "XOF" : devise;
        return switch (d) {
            case "XOF", "XAF" -> "francs CFA";
            case "EUR" -> entier > 1 ? "euros" : "euro";
            case "USD" -> entier > 1 ? "dollars" : "dollar";
            default -> d;
        };
    }

    private static void ajouter(StringBuilder sb, String morceau) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(morceau);
    }

    /** {@code accord} : « cent » et « vingt » prennent un s (deux cents, quatre-vingts) quand rien ne les suit dans le groupe et qu'ils ne précèdent pas « mille ». */
    private static String sous1000(long n, boolean accord) {
        long c = n / 100;
        long r = n % 100;
        StringBuilder sb = new StringBuilder();
        if (c > 0) {
            sb.append(c == 1 ? "cent" : UNITES[(int) c] + " cent");
            if (c > 1 && r == 0 && accord) {
                sb.append('s');
            }
        }
        if (r > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(sous100((int) r, accord));
        }
        return sb.toString();
    }

    private static String sous100(int n, boolean accord) {
        if (n < 20) {
            return UNITES[n];
        }
        int d = n / 10;
        int u = n % 10;
        if (d <= 6) {
            if (u == 0) {
                return DIZAINES[d];
            }
            return u == 1 ? DIZAINES[d] + " et un" : DIZAINES[d] + "-" + UNITES[u];
        }
        if (d == 7) {
            return u == 1 ? "soixante et onze" : "soixante-" + UNITES[10 + u];
        }
        if (d == 8) {
            return u == 0 ? (accord ? "quatre-vingts" : "quatre-vingt") : "quatre-vingt-" + UNITES[u];
        }
        return "quatre-vingt-" + UNITES[10 + u];
    }
}
