package com.example.comptable.comptabilite.parametrage;

import com.example.comptable.controle.ControlCodes;

/**
 * Code MACHINE d'un echec de resolution/validation de compte. Une explication humaine ne sert qu'a
 * l'affichage : tout traitement (Finding, controle, audit) lit ce code, jamais le texte.
 */
public enum AccountResolutionCode {
    /** Aucun mapping ni role systeme ne repond, ou compte/journal attendu absent de la configuration. */
    ACCOUNT_NOT_CONFIGURED(ControlCodes.ACCOUNT_NOT_CONFIGURED),
    /** Plusieurs mappings actifs de meme rang : jamais tranche arbitrairement. */
    ACCOUNT_MAPPING_AMBIGUOUS(ControlCodes.ACCOUNT_AMBIGUOUS),
    /** Le compte designe existe mais est desactive pour cette entreprise. */
    ACCOUNT_INACTIVE(ControlCodes.ACCOUNT_INVALID),
    /** Le compte designe n'existe pas dans le plan comptable de cette entreprise (inconnu ou d'une autre entreprise). */
    ACCOUNT_NOT_IN_PCE(ControlCodes.ACCOUNT_INVALID),
    /** Compte collectif/parent : inutilisable comme compte de detail. */
    ACCOUNT_CONTEXT_INVALID(ControlCodes.ACCOUNT_INVALID),
    /** Echec de resolution sans cause plus precise. */
    ACCOUNT_RESOLUTION_FAILED(ControlCodes.ACCOUNT_INVALID),
    /** Aucun journal du type attendu n'est configure pour l'entreprise. */
    JOURNAL_NOT_CONFIGURED(ControlCodes.ACCOUNT_NOT_CONFIGURED);

    private final String controlCode;

    AccountResolutionCode(String controlCode) {
        this.controlCode = controlCode;
    }

    /** Code de controle ({@code ControlCodes.ACCOUNT_*}) sous lequel l'echec devient un constat. */
    public String controlCode() {
        return controlCode;
    }
}
