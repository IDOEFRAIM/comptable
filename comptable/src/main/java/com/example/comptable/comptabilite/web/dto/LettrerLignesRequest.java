package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** {@code partiel} : si false (defaut), le groupe doit etre exactement equilibre (comportement
 * historique, inchange). Si true, un ecart entre debit et credit est tolere - typiquement un
 * reglement inferieur a la facture - et renvoye dans {@link LettrageResultDto#ecart()} plutot
 * que de rejeter la demande. */
public record LettrerLignesRequest(
        @NotNull UUID compteId,
        @NotEmpty List<UUID> ligneIds,
        boolean partiel
) {
}
