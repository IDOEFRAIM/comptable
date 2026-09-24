package com.example.comptable.comptabilite.operation.achat;

import com.example.comptable.comptabilite.operation.common.ReductionRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Une ligne d'une facture fournisseur multi-lignes (voir {@link AchatMultiLigneOperationRequest}).
 * {@code compteId} peut etre fourni explicitement par l'appelant (ex. via {@code
 * Article.compteAchat}) - prioritaire dans tous les cas. Absent, {@link AchatRule#construireMultiLigne}
 * tente de le resoudre via {@code AccountingAccountResolver} a partir de {@code nature} (mission
 * Etape 6, section 14) ; sans mapping ni role systeme configure pour cette entreprise, la ligne est
 * ecartee avec une explication (NEEDS_REVIEW) plutot que de forcer un compte devine. {@code tauxTva}
 * est un pourcentage explicite (ex. 18, ou 0/absent = exonere) calcule directement, meme convention
 * que {@code LigneFacture} cote vente - pas de passage par TvaRateResolver/tax_rule pour ce chemin
 * ligne-de-document.
 */
public record LigneAchatOperationRequest(
        @NotBlank String designation,
        @NotNull NatureAchat nature,
        @NotNull BigDecimal montantHt,
        BigDecimal tauxTva,
        UUID compteId,
        List<ReductionRequest> reductions
) {
}
