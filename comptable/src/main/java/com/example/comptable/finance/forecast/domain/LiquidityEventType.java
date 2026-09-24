package com.example.comptable.finance.forecast.domain;

/** Types d'evenements de liquidite detectes deterministiquement sur la serie hebdomadaire du
 * forecast (mission P2, section 20 du plan) - jamais decides par le LLM. */
public enum LiquidityEventType {
    /** Solde de cloture d'une semaine sous le seuil minimum configure (ou le seuil par defaut). */
    CASH_BELOW_THRESHOLD,
    /** Solde de cloture d'une semaine negatif - toujours HIGH, quel que soit le seuil. */
    NEGATIVE_CASH,
    /** Le solde reste sous le seuil pendant au moins deux semaines consecutives (tension
     * prolongee, distincte d'un simple creux ponctuel). */
    LIQUIDITY_GAP,
    /** Une semaine dont les sorties depassent significativement la moyenne des sorties
     * hebdomadaires recentes - explique une partie du "pourquoi" une semaine se degrade. */
    LARGE_UPCOMING_OUTFLOW,
    /** Une creance en retard, importante et/ou dont le client a un historique de paiement peu
     * fiable (ou insuffisant), pese sur la fiabilite de l'encaissement attendu. */
    RECEIVABLE_RISK
}
