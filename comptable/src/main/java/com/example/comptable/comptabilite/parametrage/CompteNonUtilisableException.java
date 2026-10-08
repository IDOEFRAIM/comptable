package com.example.comptable.comptabilite.parametrage;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Refus d'un compte, avec son code machine. Reste une {@link ResponseStatusException} (meme statut, meme message) pour les appelants existants. */
public class CompteNonUtilisableException extends ResponseStatusException {

    private final AccountResolutionCode code;

    public CompteNonUtilisableException(HttpStatus statut, AccountResolutionCode code, String message) {
        super(statut, message);
        this.code = code;
    }

    public AccountResolutionCode code() {
        return code;
    }

    /** Code d'une exception quelconque : celui qu'elle porte, sinon {@code ACCOUNT_RESOLUTION_FAILED}. */
    public static AccountResolutionCode codeDe(ResponseStatusException e) {
        return e instanceof CompteNonUtilisableException c ? c.code : AccountResolutionCode.ACCOUNT_RESOLUTION_FAILED;
    }
}
