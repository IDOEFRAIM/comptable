package com.example.comptable.agent.rapprochement.dto;

import com.example.comptable.comptabilite.domain.LigneReleve;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Miroir JSON (camelCase) de ai-service/app/schemas/rapprochement.py::LigneReleve, envoye a
 * {@code POST /api/v1/rapprochement/semantique}. */
public record LigneReleveDTO(UUID id, LocalDate dateOperation, BigDecimal montant, String libelle) {
    public static LigneReleveDTO from(LigneReleve ligne) {
        return new LigneReleveDTO(ligne.getId(), ligne.getDateOperation(), ligne.getMontant(), ligne.getLibelle());
    }
}
