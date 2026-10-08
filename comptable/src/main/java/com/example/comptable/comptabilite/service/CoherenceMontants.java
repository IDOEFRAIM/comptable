package com.example.comptable.comptabilite.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Calcul unique de l'ecart « HT + TVA - TTC » et de son explication chiffree (Phase 1, lot 1.8).
 * Mutualise UNIQUEMENT l'arithmetique et le message : la tolerance reste une politique de
 * l'appelant (le flux Documents exige l'egalite exacte, le pipeline agentique tolere un arrondi de
 * 0,02). L'alerte {@code ECART_TVA} de l'analyse de fraude est une autre regle (TVA comparee au
 * taux standard applique au HT) et n'est volontairement pas concernee.
 */
public final class CoherenceMontants {

    private CoherenceMontants() {
    }

    /** Ecart constate entre la somme HT + TVA et le TTC. */
    public record Ecart(BigDecimal ht, BigDecimal tva, BigDecimal ttc, BigDecimal somme, BigDecimal ecart) {

        /** Explication destinee au comptable : toutes les valeurs, pas un message generique. */
        public String explication() {
            return "HT + TVA (%s + %s = %s) ne correspond pas au TTC du document (%s), ecart de %s"
                    .formatted(ht.toPlainString(), tva.toPlainString(), somme.toPlainString(), ttc.toPlainString(),
                            ecart.toPlainString());
        }
    }

    /**
     * @param tolerance ecart absolu accepte (ZERO = egalite exacte)
     * @return l'ecart s'il depasse la tolerance, vide si les montants sont coherents ou incomplets
     *         (HT ou TTC absent : ce n'est pas une incoherence arithmetique, c'est un manque a signaler ailleurs)
     */
    public static Optional<Ecart> verifier(BigDecimal ht, BigDecimal tva, BigDecimal ttc, BigDecimal tolerance) {
        if (ht == null || ttc == null) {
            return Optional.empty();
        }
        BigDecimal tvaEffective = tva != null ? tva : BigDecimal.ZERO;
        BigDecimal somme = ht.add(tvaEffective);
        BigDecimal ecart = somme.subtract(ttc).abs();
        if (ecart.compareTo(tolerance) > 0) {
            return Optional.of(new Ecart(ht, tvaEffective, ttc, somme, ecart));
        }
        return Optional.empty();
    }
}
