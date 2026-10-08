package com.example.comptable.etatsfinanciers.definition;

/** Comment une ligne est calculee. */
public enum CalculationType {
    /** Somme des comptes designes par les regles de correspondance (resolveur unique). */
    MAPPED,
    /** Formule sur d'autres lignes (soldes intermediaires, totaux). */
    FORMULA,
    /** Calculee par la methode officielle a partir de donnees metier (TFT) : jamais par un prefixe de compte. */
    METHOD,
    /** Calculee a partir de soldes de bilan d'autres etats (tresorerie nette). */
    BALANCE_DERIVED,
    /** Identite comptable portee par la definition (ex. CJ : resultat = -(classe 13 + classes 6, 7 et 8)) ; le calcul est celui du service, jamais un prefixe disperse. */
    RESULT_IDENTITY,
    /** Ligne de structure : code et libelle verifies, calcul non defini (non publie ou bloque) ; voir {@code calculationBlockedReason}. */
    STRUCTURE_ONLY
}
