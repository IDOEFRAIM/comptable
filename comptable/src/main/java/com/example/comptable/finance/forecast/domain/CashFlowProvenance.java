package com.example.comptable.finance.forecast.domain;

/**
 * Provenance d'un flux de tresorerie projete (mission P2, "13-Week Cash Forecast", section 4 du
 * plan) - la distinction la plus importante de tout le forecast : le LLM/futur CFO doit toujours
 * savoir si un chiffre est certain, attendu ou estime, jamais une masse indifferenciee.
 * <ul>
 *   <li>{@link #KNOWN} : date ET montant connus avec certitude suffisante (facture non encore
 *   echue, echeance fiscale deja programmee).</li>
 *   <li>{@link #EXPECTED} : montant reel mais date de realisation incertaine (creance deja en
 *   retard - la date de collecte est estimee, jamais garantie).</li>
 *   <li>{@link #PREDICTED} : ni le montant ni la date exacte ne sont contractuels - estimation a
 *   partir d'un historique recurrent (charges recurrentes detectees).</li>
 * </ul>
 */
public enum CashFlowProvenance {
    KNOWN,
    EXPECTED,
    PREDICTED
}
