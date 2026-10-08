package com.example.comptable.document.ocr;

/**
 * Extraction des donnees comptables d'une piece justificative (montants, date).
 * Le resultat est toujours a valider par un humain avant generation d'ecriture ;
 * cette brique ne fait qu'accelerer la saisie, elle ne la remplace pas.
 */
public interface OcrExtractionService {

    OcrResult extraire(byte[] contenu, String contentType);

    /** Nom du moteur, conserve comme source de chaque champ extrait. */
    default String nomMoteur() {
        return getClass().getSimpleName();
    }
}
