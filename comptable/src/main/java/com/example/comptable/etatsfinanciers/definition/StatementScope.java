package com.example.comptable.etatsfinanciers.definition;

import java.util.List;

/** Perimetre de pertinence d'un etat : classes de comptes dont un solde non mappe est une anomalie (jamais « range dans la premiere rubrique plausible »). */
public record StatementScope(List<Integer> accountClasses, List<String> excludedPrefixes) {

    public StatementScope {
        accountClasses = accountClasses == null ? List.of() : List.copyOf(accountClasses);
        excludedPrefixes = excludedPrefixes == null ? List.of() : List.copyOf(excludedPrefixes);
    }

    public StatementScope(List<Integer> accountClasses) {
        this(accountClasses, List.of());
    }

    /** Classe pertinente et compte non « consomme » par une ligne d'identite (ex. classe 13 pour CJ) : seuls ces comptes peuvent etre non mappes. */
    public boolean concerne(String numeroCompte) {
        return !numeroCompte.isEmpty() && Character.isDigit(numeroCompte.charAt(0)) && accountClasses.contains(Character.getNumericValue(numeroCompte.charAt(0)))
                && excludedPrefixes.stream().noneMatch(numeroCompte::startsWith);
    }
}
