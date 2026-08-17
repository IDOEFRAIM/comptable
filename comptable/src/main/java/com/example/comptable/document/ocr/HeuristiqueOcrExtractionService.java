package com.example.comptable.document.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation v1 : extraction de texte PDF (PDFBox) puis reperage des montants/date
 * par expressions regulieres sur les libelles francophones les plus courants.
 * Volontairement simple et transparente sur ses limites : ne devine rien, remonte
 * "non trouve" plutot qu'une valeur inventee.
 * <p>
 * Active par defaut ({@code agent.vlm.enabled=false}) ; desactivee automatiquement quand
 * l'extraction par microservice IA
 * ({@link com.example.comptable.agent.extraction.AiServiceOcrExtractionService}) est
 * active, pour qu'une seule implementation d'{@link OcrExtractionService} soit jamais
 * presente dans le contexte Spring (sinon injection ambigue dans DocumentService).
 */
@Service
@ConditionalOnProperty(name = "agent.vlm.enabled", havingValue = "false", matchIfMissing = true)
public class HeuristiqueOcrExtractionService implements OcrExtractionService {

    // Visibilite package (non private) : reutilisees telles quelles par les tests, plutot que de
    // dupliquer les expressions regulieres exactes dans une classe de test separee.
    static final Pattern MONTANT_TTC = montantApres("total\\s*ttc|net\\s*a\\s*payer|montant\\s*ttc");
    static final Pattern MONTANT_HT = montantApres("total\\s*ht|montant\\s*ht");
    static final Pattern MONTANT_TVA = montantApres("tva");
    private static final Pattern DATE_JJMMAAAA = Pattern.compile("(\\d{2})[/.-](\\d{2})[/.-](\\d{4})");

    @Override
    public OcrResult extraire(byte[] contenu, String contentType) {
        if (!"application/pdf".equals(contentType)) {
            return OcrResult.echec(
                    "Extraction automatique non disponible pour les photos pour le moment (Tesseract/Textract a integrer) ; saisie manuelle requise.");
        }

        String texte;
        try {
            texte = extraireTexte(contenu);
        } catch (IOException e) {
            return OcrResult.echec("PDF illisible ou corrompu : " + e.getMessage());
        }

        if (texte.isBlank()) {
            // Distinct du cas "texte present mais aucun montant repere" ci-dessous : un PDF sans
            // aucun texte extractible (page scannee, ou generee comme rendu graphique/canvas
            // plutot que texte reel) ne peut structurellement pas etre lu par une regex - c'est
            // exactement le cas d'usage vise par le VLM (Sprint 1), pas un defaut de motif.
            return OcrResult.echec(
                    "Ce PDF ne contient aucun texte extractible (probablement une image scannee ou un rendu "
                            + "graphique) ; activez l'extraction par IA (agent.vlm.enabled) ou saisissez les montants manuellement.");
        }

        BigDecimal ttc = extraireMontant(texte, MONTANT_TTC);
        BigDecimal ht = extraireMontant(texte, MONTANT_HT);
        BigDecimal tva = extraireMontant(texte, MONTANT_TVA);
        LocalDate date = extraireDate(texte);

        boolean reussite = ttc != null || ht != null;
        String message = reussite
                ? "Extraction heuristique reussie a partir du texte du PDF ; a verifier avant validation."
                : "Aucun montant reconnu dans le PDF ; saisie manuelle requise.";
        // Confiance fixe et volontairement moderee : ce moteur ne s'auto-evalue pas (simple
        // reperage regex), contrairement au VLM qui peut estimer sa propre certitude.
        double confiance = reussite ? 0.6 : 0.0;

        return new OcrResult(ht, tva, ttc, date, reussite, message, confiance, null);
    }

    private String extraireTexte(byte[] contenu) throws IOException {
        try (PDDocument document = Loader.loadPDF(contenu)) {
            return normaliserEspaces(new PDFTextStripper().getText(document));
        }
    }

    /** Espaces typographiques (insecable U+00A0, fine U+202F) frequents dans les montants
     * formates a la francaise/OHADA ("22 012 FCFA") : normalises en espace ASCII simple, le
     * seul reconnu par le caractere [0-9 .,] des regex de capture - sans cette normalisation un
     * montant de 4 chiffres ou plus n'est jamais capture. Visibilite package pour etre testee. */
    static String normaliserEspaces(String texte) {
        return texte.replace(' ', ' ').replace(' ', ' ');
    }

    /** Visibilite package (non private) pour etre testee directement sur des chaines de texte,
     * sans avoir a generer un PDF reel juste pour exercer la logique de reperage. */
    BigDecimal extraireMontant(String texte, Pattern pattern) {
        Matcher matcher = pattern.matcher(texte);
        if (!matcher.find()) {
            return null;
        }
        return normaliserMontant(matcher.group(1));
    }

    BigDecimal normaliserMontant(String brut) {
        String nettoye = brut.trim().replaceAll("\\s", "");
        boolean aVirgule = nettoye.contains(",");
        boolean aPoint = nettoye.contains(".");
        if (aVirgule && aPoint) {
            nettoye = nettoye.replace(".", "").replace(",", ".");
        } else if (aVirgule) {
            nettoye = nettoye.replace(",", ".");
        }
        try {
            return new BigDecimal(nettoye);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate extraireDate(String texte) {
        Matcher matcher = DATE_JJMMAAAA.matcher(texte);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(1) + "/" + matcher.group(2) + "/" + matcher.group(3),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) {
            return null;
        }
    }

    private static Pattern montantApres(String libelles) {
        // Le groupe exige au moins 3 caracteres pour eviter de capturer un taux court
        // (ex: le "18" de "TVA 18%") a la place du montant qui suit.
        return Pattern.compile("(?i)(?:" + libelles + ")\\D{0,15}([0-9][0-9 .,]{1,}[0-9])");
    }
}
