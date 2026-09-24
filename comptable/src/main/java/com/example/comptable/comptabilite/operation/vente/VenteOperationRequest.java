package com.example.comptable.comptabilite.operation.vente;

import com.example.comptable.comptabilite.operation.common.FraisAccessoireRequest;
import com.example.comptable.comptabilite.operation.common.ReductionRequest;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record VenteOperationRequest(
        @NotNull NatureVente natureVente,
        @NotNull UUID clientId,
        @NotNull LocalDate date,
        String reference,
        @NotNull String libelle,
        @NotNull BigDecimal montantHt,
        @NotNull UUID compteProduitId,
        UUID tauxTvaId,
        BigDecimal montantTtcAttendu,
        List<FraisAccessoireRequest> fraisAccessoires,
        List<ReductionRequest> reductions
) {
}
