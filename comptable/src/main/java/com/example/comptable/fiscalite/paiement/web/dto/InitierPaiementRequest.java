package com.example.comptable.fiscalite.paiement.web.dto;

import com.example.comptable.fiscalite.paiement.domain.PaiementFournisseur;
import jakarta.validation.constraints.NotNull;

public record InitierPaiementRequest(
        @NotNull PaiementFournisseur fournisseur,
        /** Requis pour {@link PaiementFournisseur#FASO_ARZEKA} ; ignore pour e-Syntaxe. */
        String numeroTelephonePayeur
) {
}
