package com.example.comptable.comptabilite.operation.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Frais accessoire (transport, port, emballage...) rattache a un achat ou une vente. {@code
 * compteId} est toujours fourni par l'appelant et valide comme tout autre compte - jamais devine.
 * {@code inclusDansBaseTva=true} ajoute {@code montantHt} a la base TVA principale de l'operation
 * (une seule ligne TVA consolidee) ; {@code false} calcule la contribution TVA propre du frais
 * (via {@code tauxTvaId}) mais la fusionne dans la meme ligne de compte TVA plutot que de creer
 * une ligne separee pour le meme compte.
 */
public record FraisAccessoireRequest(
        @NotBlank String type,
        @NotNull BigDecimal montantHt,
        UUID tauxTvaId,
        @NotNull UUID compteId,
        boolean inclusDansBaseTva
) {
}
