package com.example.comptable.comptabilite.operation.avoir;

import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Avoir generique (independant de toute Facture d'origine, contrairement a FactureAvoirService
 * qui reste le chemin dedie aux avoirs sur facture de vente deja tracee). {@code type} doit etre
 * AVOIR_VENTE ou AVOIR_ACHAT. {@code compteId} designe le compte produit (vente) ou charge (achat)
 * a inverser - toujours fourni explicitement, aucune trace de document d'origine ici.
 */
public record AvoirOperationRequest(
        @NotNull AccountingOperationType type,
        @NotNull UUID tiersId,
        @NotNull LocalDate date,
        String reference,
        @NotNull String libelle,
        @NotNull BigDecimal montantHt,
        @NotNull UUID compteId,
        UUID tauxTvaId,
        BigDecimal montantTtcAttendu
) {
}
