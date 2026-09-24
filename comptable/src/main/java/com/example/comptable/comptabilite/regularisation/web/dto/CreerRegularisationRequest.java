package com.example.comptable.comptabilite.regularisation.web.dto;

import com.example.comptable.comptabilite.regularisation.domain.RegularisationType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreerRegularisationRequest(
        @NotNull UUID exerciceId,
        @NotNull RegularisationType type,
        @NotNull LocalDate dateOrigine,
        LocalDate dateDebut,
        LocalDate dateFin,
        @NotNull BigDecimal montant,
        @NotNull String libelle,
        @NotNull UUID compteOrigineId,
        @NotNull UUID compteContrepartieId,
        Boolean repriseAutomatique
) {
}
