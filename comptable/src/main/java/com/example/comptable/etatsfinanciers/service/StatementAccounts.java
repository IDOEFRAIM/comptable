package com.example.comptable.etatsfinanciers.service;

/**
 * Regles d'identification d'un compte par sa classe, imprimees dans la source (identite du resultat : classe 13 et classes 6, 7, 8) : un seul endroit, aucun autre test de prefixe de
 * classe dans les services d'etats financiers (les rattachements aux postes passent par les definitions et le resolveur).
 */
final class StatementAccounts {

    private StatementAccounts() {
    }

    /** Compte de gestion : charges (6), produits (7), comptes HAO et impots (8). */
    static boolean estCompteDeGestion(String numero) {
        return numero.startsWith("6") || numero.startsWith("7") || numero.startsWith("8");
    }

    /** Compte entrant dans l'identite du resultat du Bilan : report du resultat en instance (13) et comptes de gestion. */
    static boolean entreDansLeResultat(String numero) {
        return numero.startsWith("13") || estCompteDeGestion(numero);
    }
}
