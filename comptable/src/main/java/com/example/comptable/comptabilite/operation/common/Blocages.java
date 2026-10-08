package com.example.comptable.comptabilite.operation.common;

import com.example.comptable.comptabilite.parametrage.AccountResolutionCode;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu;

import java.util.ArrayList;
import java.util.List;

/**
 * Liste des explications d'une regle ({@code List<String>} pour l'affichage) qui conserve, a l'indice
 * correspondant, un CODE MACHINE quand l'explication decrit un probleme d'imputation. Les consommateurs
 * (constats, audit) lisent {@link #codes()} ; ils ne parsent jamais le texte.
 */
public class Blocages extends ArrayList<String> {

    private final List<AccountResolutionCode> codes = new ArrayList<>();

    /** Explication sans code machine (ex. TVA non calculee, escompte non gere). */
    @Override
    public boolean add(String explication) {
        codes.add(null);
        return super.add(explication);
    }

    /** Explication d'un probleme d'imputation, avec son code. */
    public void ajouter(AccountResolutionCode code, String explication) {
        codes.add(code);
        super.add(explication);
    }

    /** Compte non resolu/inutilisable : explication standard + code de la resolution. */
    public void compte(CompteResolu compte, String libelleCompte) {
        ajouter(compte.code(), compte.explicationBlocage(libelleCompte));
    }

    /** Codes alignes sur les explications ({@code null} = pas un probleme d'imputation). */
    public List<AccountResolutionCode> codes() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(codes));
    }
}
