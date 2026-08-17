package com.example.comptable.dashboard.web.dto;

import com.example.comptable.comptabilite.web.dto.SoldeDto;
import com.example.comptable.fiscalite.web.dto.DeclarationFiscaleDto;

import java.util.List;

public record TableauDeBordDto(
        long documentsATraiter,
        long documentsEnErreur,
        long ecrituresBrouillon,
        SoldeDto soldeTresorerie,
        List<DeclarationFiscaleDto> prochainesEcheances,
        List<DeclarationFiscaleDto> echeancesEnRetard
) {
}
