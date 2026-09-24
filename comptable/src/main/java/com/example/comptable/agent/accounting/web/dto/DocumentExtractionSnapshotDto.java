package com.example.comptable.agent.accounting.web.dto;

import com.example.comptable.document.domain.TypeDocument;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Instantane de l'extraction deja persistee d'un {@code Document} (STEP 6 du plan
 * d'implementation, node Python load_extraction) - ne relance jamais une extraction VLM, ne lit
 * que ce que {@code DocumentService} a deja enregistre (montants/date en colonnes dediees,
 * fournisseur/devise/modePaiement/confiance dans le JSON {@code donneesOcr}). {@code typeDocument}
 * est la classification metier Java (FACTURE_ACHAT/...), distincte du typage visuel du VLM
 * (TypeDocumentDetecte, cote ai-service) qui n'est pas persiste au-dela de l'extraction elle-meme.
 */
public record DocumentExtractionSnapshotDto(
        UUID documentId,
        UUID companyId,
        TypeDocument typeDocument,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        LocalDate datePiece,
        String fournisseur,
        String devise,
        String modePaiement,
        Double confiance
) {
}
