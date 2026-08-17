package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceLigneDto(
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        SoldeDto soldeOuverture,
        BigDecimal mouvementDebit,
        BigDecimal mouvementCredit,
        SoldeDto soldeCloture
) {
}
