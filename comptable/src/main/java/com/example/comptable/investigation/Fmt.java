package com.example.comptable.investigation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Montants lisibles dans les explications (« 700 000 », « -250 000 »), jamais « 700000.00 ». */
final class Fmt {

    private Fmt() {
    }

    static String m(BigDecimal v) {
        if (v == null) {
            return "";
        }
        BigDecimal r = v.stripTrailingZeros();
        boolean entier = r.scale() <= 0;
        String brut = (entier ? r.setScale(0, RoundingMode.HALF_UP) : r.setScale(2, RoundingMode.HALF_UP)).toPlainString();
        boolean negatif = brut.startsWith("-");
        String s = negatif ? brut.substring(1) : brut;
        String[] parties = s.split("\\.");
        StringBuilder groupe = new StringBuilder();
        String ent = parties[0];
        for (int i = 0; i < ent.length(); i++) {
            if (i > 0 && (ent.length() - i) % 3 == 0) {
                groupe.append('\u00A0');
            }
            groupe.append(ent.charAt(i));
        }
        return (negatif ? "-" : "") + groupe + (parties.length > 1 ? "," + parties[1] : "");
    }
}
