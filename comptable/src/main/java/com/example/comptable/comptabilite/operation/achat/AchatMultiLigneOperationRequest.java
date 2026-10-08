package com.example.comptable.comptabilite.operation.achat;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import com.example.comptable.comptabilite.operation.common.AccountingSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Facture fournisseur multi-lignes multi-natures (marchandises + services + frais + immobilisation
 * sur une seule piece) - voir {@link AchatRule#construireMultiLigne}. {@code source}
 * porte la cause metier (idempotence : une meme cause ne produit jamais deux ecritures) ; nullable pour une
 * simple simulation sans consequence comptable (ex. reconstruction d'une facture pour un avoir).
 */
public record AchatMultiLigneOperationRequest(
        @NotNull UUID fournisseurId,
        @NotNull LocalDate date,
        String reference,
        @NotNull String libelle,
        @NotEmpty List<LigneAchatOperationRequest> lignes,
        BigDecimal montantTtcAttendu,
        AccountingSource source
) {
}
