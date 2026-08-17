package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReleveBancaireDto(
        UUID id,
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        String libelle,
        LocalDate dateDebut,
        LocalDate dateFin,
        BigDecimal soldeDebut,
        BigDecimal soldeFin,
        BigDecimal soldeComptable,
        BigDecimal ecart,
        List<LigneReleveDto> lignes
) {
}
