package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ImporterReleveRequest(
        UUID compteId,
        String libelle,
        LocalDate dateDebut,
        LocalDate dateFin,
        BigDecimal soldeDebut,
        BigDecimal soldeFin
) {
}
