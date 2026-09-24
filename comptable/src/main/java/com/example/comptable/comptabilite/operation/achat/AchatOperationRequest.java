package com.example.comptable.comptabilite.operation.achat;

import com.example.comptable.comptabilite.operation.common.FraisAccessoireRequest;
import com.example.comptable.comptabilite.operation.common.ReductionRequest;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AchatOperationRequest(
        @NotNull NatureAchat natureAchat,
        @NotNull UUID fournisseurId,
        @NotNull LocalDate date,
        String reference,
        @NotNull String libelle,
        @NotNull BigDecimal montantHt,
        @NotNull UUID compteChargeId,
        UUID tauxTvaId,
        BigDecimal montantTtcAttendu,
        List<FraisAccessoireRequest> fraisAccessoires,
        List<ReductionRequest> reductions
) {
}
