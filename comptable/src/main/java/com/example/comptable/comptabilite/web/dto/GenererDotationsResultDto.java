package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Resultat d'une generation de dotations : une ecriture par immobilisation (tracabilite et idempotence par actif). */
public record GenererDotationsResultDto(List<UUID> ecritureIds, int annee, BigDecimal totalDotations, int nombreImmobilisations) {
}
