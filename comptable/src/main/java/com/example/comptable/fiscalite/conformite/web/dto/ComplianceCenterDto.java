package com.example.comptable.fiscalite.conformite.web.dto;

import com.example.comptable.agent.finding.web.dto.FindingDto;

import java.util.List;

/** Mission P1 (section 30) : vue consolidee des obligations fiscales - agregation de lecture sur
 * {@code DeclarationFiscale} (deja existant) et les {@code Finding} de type TAX_REVIEW (deja
 * produits par ControlEngine en P0), jamais un second modele de donnees. */
public record ComplianceCenterDto(
        List<ObligationFiscaleDto> obligations,
        List<FindingDto> anomalies
) {
}
