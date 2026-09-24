package com.example.comptable.comptabilite.operation.achat;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Facture fournisseur multi-lignes multi-natures (marchandises + services + frais + immobilisation
 * sur une seule piece) - voir {@link AchatRule#construireMultiLigne}. {@code sourceType}/{@code
 * sourceId} portent l'idempotence (une meme source ne doit jamais produire deux ecritures) et
 * restent nullables pour un usage manuel futur sans document d'origine.
 */
public record AchatMultiLigneOperationRequest(
        @NotNull UUID fournisseurId,
        @NotNull LocalDate date,
        String reference,
        @NotNull String libelle,
        @NotEmpty List<LigneAchatOperationRequest> lignes,
        BigDecimal montantTtcAttendu,
        String sourceType,
        UUID sourceId
) {
}
