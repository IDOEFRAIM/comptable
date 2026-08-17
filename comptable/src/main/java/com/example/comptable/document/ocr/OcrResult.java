package com.example.comptable.document.ocr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Resultat d'extraction, quel que soit le moteur utilise en interne (regex, ai-service...).
 * {@code reussite} indique si des donnees exploitables ont ete trouvees ; {@code message}
 * explique toujours le resultat pour l'utilisateur humain qui devra de toute facon valider avant
 * comptabilisation. {@code confiance} (0.0 a 1.0) permet aux moteurs capables de s'auto-evaluer
 * (VLM) de signaler un doute exploitable en aval (ex: bascule automatique vers une revue humaine
 * sous un certain seuil) ; les moteurs qui ne savent pas s'auto-evaluer renvoient une valeur
 * fixe raisonnable.
 * <p>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} : {@code AiServiceOcrExtractionService}
 * deserialise directement la reponse JSON du microservice IA dans ce record via Jackson (pas de
 * DTO intermediaire) ; cette reponse porte des champs additionnels
 * (fournisseur/devise/modePaiement, cf. ai-service/app/schemas/extraction.py) que ce record ne
 * modelise pas - sans cette annotation, Jackson echouerait par defaut sur toute propriete
 * inconnue (voir JacksonConfig, qui ne desactive pas ce comportement globalement).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OcrResult(
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        LocalDate datePiece,
        boolean reussite,
        String message,
        double confiance,
        /** Ajoute par la mission P0 (AccountingProposalBuilder) : jusque-la silencieusement
         * ignore par ce record (ignoreUnknown=true) meme quand ai-service le renvoyait deja pour
         * l'upload web - null pour {@link com.example.comptable.document.ocr.HeuristiqueOcrExtractionService},
         * qui ne peut pas le detecter. */
        String fournisseur
) {
    public static OcrResult echec(String message) {
        return new OcrResult(null, null, null, null, false, message, 0.0, null);
    }
}
