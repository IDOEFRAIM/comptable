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
        String type,
        LocalDate dateAcquisition,
        LocalDate dateMiseEnService,
        BigDecimal valeurOrigine,
        int dureeAnnees,
        String mode,
        String statut,
        UUID acquisitionProposalId,
        UUID acquisitionEcritureId,
        String acquisitionSource,
        LocalDate dateSortie,
        String motifSortie,
        BigDecimal montantCession,
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
                immo.getType().name(),
                immo.getDateAcquisition(),
                immo.getDateMiseEnService(),
                immo.getValeurOrigine(),
                immo.getDureeAnnees(),
                immo.getMode().name(),
                immo.getStatut().name(),
                immo.getAcquisitionProposal() != null ? immo.getAcquisitionProposal().getId() : null,
                immo.getAcquisitionProposal() != null && immo.getAcquisitionProposal().getEcriture() != null
                        ? immo.getAcquisitionProposal().getEcriture().getId() : null,
                immo.getAcquisitionProposal() != null
                        ? immo.getAcquisitionProposal().getSourceType() + "/" + immo.getAcquisitionProposal().getSourceId()
                                + "/" + immo.getAcquisitionProposal().getSourceEvent() : null,
                immo.getDateSortie(),
                immo.getMotifSortie() != null ? immo.getMotifSortie().name() : null,
                immo.getMontantCession(),
                vncActuelle,
                plan
        );
    }
}
