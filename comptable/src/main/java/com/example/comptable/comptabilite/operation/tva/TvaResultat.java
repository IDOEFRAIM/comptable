package com.example.comptable.comptabilite.operation.tva;

import com.example.comptable.comptabilite.domain.CompteComptable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultat de la resolution TVA pour une operation. {@code nonSupporte=true} signifie qu'aucune
 * regle fiscale applicable ou aucun compte systeme configure n'a ete trouve - le moteur ne calcule
 * jamais de TVA dans ce cas, {@code motif} explique pourquoi (jamais un taux devine). {@code
 * compte} est l'entite deja resolue et deja tenant-verifiee par CompteSystemeResolver - un appelant
 * ne doit jamais la recharger/revalider separement.
 */
public record TvaResultat(
        BigDecimal taux,
        BigDecimal montantTva,
        CompteComptable compte,
        UUID taxRuleId,
        boolean nonSupporte,
        String motif,
        boolean sourceDemo
) {
    public static TvaResultat nonSupporte(String motif) {
        return new TvaResultat(null, null, null, null, true, motif, false);
    }
}
