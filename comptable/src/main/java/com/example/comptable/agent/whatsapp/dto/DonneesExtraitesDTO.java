package com.example.comptable.agent.whatsapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Miroir JSON (camelCase) de ai-service/app/schemas/ingestion.py::DonneesExtraites : resultat
 * de l'extraction VLM deja effectuee par le microservice IA pour une piece WhatsApp.
 * {@code fournisseur}/{@code devise}/{@code modePaiement} n'existent pas dans
 * {@link com.example.comptable.document.ocr.OcrResult} (record Java d'origine, toujours utilise
 * par le chemin d'upload web) : ce sont des champs additionnels propres a ce canal, stockes dans
 * {@code Document.donneesOcr} (JSON libre) plutot que d'ajouter des colonnes dediees - cf.
 * DocumentService.appliquerExtractionExterne.
 * <p>
 * {@code confiance} n'est volontairement pas borne par {@code @DecimalMin}/{@code @DecimalMax} :
 * ces annotations ne sont utilisees ailleurs dans ce codebase que sur des champs
 * {@link BigDecimal} (montants), jamais sur un {@code double} primitif - reste dans le meme
 * style plutot que de parier sur ce comportement d'Hibernate Validator. Une valeur hors
 * [0.0, 1.0] n'est de toute facon pas dangereuse ici : {@code DocumentService.versOcrResult} ne
 * fait que comparer {@code confiance > 0.0}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DonneesExtraitesDTO(
        String fournisseur,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        String devise,
        String modePaiement,
        LocalDate datePiece,
        double confiance
) {
}
