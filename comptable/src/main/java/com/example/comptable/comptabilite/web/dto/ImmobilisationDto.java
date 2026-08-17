package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.Immobilisation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ImmobilisationDto(
        UUID id,
        String compteNumero,
        String compteIntitule,
        String compteAmortissementNumero,
        String libelle,
        LocalDate dateAcquisition,
        BigDecimal valeurOrigine,
        int dureeAnnees,
        String mode,
        String statut,
        BigDecimal valeurNetteComptableActuelle,
        List<LigneAmortissementDto> plan
) {
    public static ImmobilisationDto from(Immobilisation immo, BigDecimal vncActuelle, List<LigneAmortissementDto> plan) {
        return new ImmobilisationDto(
                immo.getId(),
                immo.getCompte().getNumero(),
                immo.getCompte().getIntitule(),
                immo.getCompteAmortissement().getNumero(),
                immo.getLibelle(),
                immo.getDateAcquisition(),
                immo.getValeurOrigine(),
                immo.getDureeAnnees(),
                immo.getMode().name(),
                immo.getStatut().name(),
                vncActuelle,
                plan
        );
    }
}
