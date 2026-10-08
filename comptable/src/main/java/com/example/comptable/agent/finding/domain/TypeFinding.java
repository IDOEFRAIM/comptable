package com.example.comptable.agent.finding.domain;

/** Categories de "quelque chose necessite l'attention du comptable" (mission P0, sections 7/18) -
 * fusion volontaire de WorkItem et AccountingFinding/AiFinding en un seul modele, cf. Finding. */
public enum TypeFinding {
    /** Decision AMBIGUOUS_MATCH du Universal Matching Engine : plusieurs candidats plausibles. */
    MATCH_AMBIGUOUS,
    /** NO_MATCH/AI_UNAVAILABLE interprete par le Missing Evidence Engine : preuve manquante. */
    MISSING_EVIDENCE,
    /** Anomalie probabiliste (montant/fournisseur/frequence) - jamais nommee "fraude" par defaut. */
    ANOMALY,
    /** Document a score de fraude CRITIQUE ou faible confiance d'extraction, non encore resolu. */
    DOCUMENT_REVIEW,
    /** Declaration fiscale manquante ou en retard. */
    TAX_REVIEW,
    /** Groupe de lettrage ambigu (plus d'une ligne de chaque cote au meme montant). */
    LETTRAGE_AMBIGU,
    /** Ecriture BROUILLON ancienne, jamais validee. */
    ECRITURE_A_CONTROLER,
    /** Blocage identifie lors du calcul de Closing Readiness. */
    CLOSING_BLOCKER,
    /** Imputation a confirmer : compte non configure/ambigu/invalide, nature a valider (jamais un compte devine). */
    ACCOUNT_REVIEW,
    /** Ecart bancaire : lignes de releve non pointees, solde du releve different du solde comptable. */
    BANK_DIFFERENCE,
    /** Donnee incoherente detectee de facon deterministe (ex. HT + TVA different du TTC). */
    DATA_INCONSISTENCY
}
