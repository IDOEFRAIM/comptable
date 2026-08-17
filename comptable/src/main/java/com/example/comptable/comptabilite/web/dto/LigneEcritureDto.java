package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.LigneEcriture;

import java.math.BigDecimal;
import java.util.UUID;

public record LigneEcritureDto(
        UUID id,
        UUID compteId,
        String compteNumero,
        String compteIntitule,
        String libelle,
        BigDecimal debit,
        BigDecimal credit,
        String lettrage,
        Integer numeroLigne,
        UUID tiersId,
        String tiersNom
) {
    public static LigneEcritureDto from(LigneEcriture ligne) {
        return new LigneEcritureDto(
                ligne.getId(),
                ligne.getCompte().getId(),
                ligne.getCompte().getNumero(),
                ligne.getCompte().getIntitule(),
                ligne.getLibelle(),
                ligne.getDebit(),
                ligne.getCredit(),
                ligne.getLettrage(),
                ligne.getNumeroLigne(),
                ligne.getTiers() != null ? ligne.getTiers().getId() : null,
                ligne.getTiers() != null ? ligne.getTiers().getNom() : null
        );
    }
}
