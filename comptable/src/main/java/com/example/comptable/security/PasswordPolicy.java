package com.example.comptable.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Politique minimale de mot de passe (pilote) : longueur suffisante, pas l'adresse e-mail, au moins deux classes de caractères. Appliquée au changement et à la réinitialisation. */
public final class PasswordPolicy {

    public static final int LONGUEUR_MIN = 12;

    private PasswordPolicy() {
    }

    public static void valider(String motDePasse, String email) {
        if (motDePasse == null || motDePasse.length() < LONGUEUR_MIN) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le mot de passe doit contenir au moins " + LONGUEUR_MIN + " caractères");
        }
        if (motDePasse.length() > 128) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le mot de passe est trop long (128 caractères au plus)");
        }
        if (email != null && motDePasse.equalsIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le mot de passe ne peut pas être l'adresse e-mail");
        }
        int classes = (motDePasse.chars().anyMatch(Character::isLowerCase) ? 1 : 0) + (motDePasse.chars().anyMatch(Character::isUpperCase) ? 1 : 0)
                + (motDePasse.chars().anyMatch(Character::isDigit) ? 1 : 0) + (motDePasse.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0);
        if (classes < 2) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le mot de passe doit mélanger au moins deux types de caractères (minuscules, majuscules, chiffres, symboles)");
        }
    }
}
