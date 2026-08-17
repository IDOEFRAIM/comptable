package com.example.comptable.copilot;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Partage entre {@link IntentClassifier} (Copilot comptable) et {@link CabinetIntentClassifier}
 * (Copilot cabinet) - meme normalisation, jamais dupliquee. */
final class TexteNormalizer {

    private static final Pattern DIACRITIQUES = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    private TexteNormalizer() {
    }

    static String normaliser(String texte) {
        if (texte == null) {
            return "";
        }
        String sansAccents = DIACRITIQUES.matcher(Normalizer.normalize(texte, Normalizer.Form.NFD)).replaceAll("");
        return sansAccents.toLowerCase();
    }
}
