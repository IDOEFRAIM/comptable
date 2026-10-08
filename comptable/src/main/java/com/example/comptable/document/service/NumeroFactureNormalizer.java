package com.example.comptable.document.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalisation SIMPLE d'un numero de facture, pour comparer deux saisies d'un meme numero : accents retires,
 * majuscules, tout separateur ou espace supprime. Volontairement pas de fuzzy matching : deux numeros
 * normalises egaux sont identiques, sinon ils different. La valeur d'origine est toujours conservee a cote.
 * Exemple : {@code "FAC- 001/2026"} -&gt; {@code "FAC0012026"}.
 */
public final class NumeroFactureNormalizer {

    private NumeroFactureNormalizer() {
    }

    /** @return le numero normalise, ou {@code null} s'il est vide ou ne contient ni lettre ni chiffre. */
    public static String normaliser(String numero) {
        if (numero == null) {
            return null;
        }
        String sansAccents = Normalizer.normalize(numero, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String normalise = sansAccents.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return normalise.isEmpty() ? null : normalise;
    }
}
