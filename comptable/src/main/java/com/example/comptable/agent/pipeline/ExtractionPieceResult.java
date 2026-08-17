package com.example.comptable.agent.pipeline;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Donnees d'extraction portees par {@link PieceJustificativeState} tout au long du graphe.
 * Volontairement distinct de {@code document.ocr.OcrResult} : ce dernier est un contrat entre
 * un moteur d'OCR/VLM et {@code DocumentService} au moment de l'ingestion, tandis que ce record
 * est l'etat metier du pipeline agentique une fois l'extraction deja realisee et persistee sur
 * le {@code Document} (cf. {@link ExtractionNode}, qui fait le pont entre les deux).
 */
public record ExtractionPieceResult(
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        LocalDate datePiece,
        double confiance
) {
}
