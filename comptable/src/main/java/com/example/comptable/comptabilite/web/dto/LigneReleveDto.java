package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record LigneReleveDto(
        UUID id,
        LocalDate date,
        String libelle,
        BigDecimal montant,
        boolean pointee,
        UUID ligneEcritureId,
        String ecritureNumeroPiece,
        String ecritureLibelle
) {
}
