package com.example.comptable.comptabilite.regularisation.web.dto;

import com.example.comptable.comptabilite.regularisation.domain.Regularisation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RegularisationDto(
        UUID id,
        UUID exerciceId,
        String type,
        LocalDate dateOrigine,
        LocalDate dateDebut,
        LocalDate dateFin,
        BigDecimal montant,
        BigDecimal montantExercice,
        BigDecimal montantFutur,
        String libelle,
        UUID compteOrigineId,
        String compteOrigineNumero,
        UUID compteContrepartieId,
        String compteContrepartieNumero,
        String statut,
        UUID propositionId,
        UUID ecritureId,
        boolean repriseAutomatique,
        LocalDate dateReprise,
        UUID propositionRepriseId,
        UUID ecritureRepriseId,
        OffsetDateTime createdAt
) {
    public static RegularisationDto from(Regularisation regularisation) {
        return new RegularisationDto(
                regularisation.getId(),
                regularisation.getExercice().getId(),
                regularisation.getType().name(),
                regularisation.getDateOrigine(),
                regularisation.getDateDebut(),
                regularisation.getDateFin(),
                regularisation.getMontant(),
                regularisation.getMontantExercice(),
                regularisation.getMontantFutur(),
                regularisation.getLibelle(),
                regularisation.getCompteOrigine().getId(),
                regularisation.getCompteOrigine().getNumero(),
                regularisation.getCompteContrepartie().getId(),
                regularisation.getCompteContrepartie().getNumero(),
                regularisation.getStatut().name(),
                regularisation.getProposition() != null ? regularisation.getProposition().getId() : null,
                regularisation.getProposition() != null && regularisation.getProposition().getEcriture() != null
                        ? regularisation.getProposition().getEcriture().getId() : null,
                regularisation.isRepriseAutomatique(),
                regularisation.getDateReprise(),
                regularisation.getPropositionReprise() != null ? regularisation.getPropositionReprise().getId() : null,
                regularisation.getPropositionReprise() != null && regularisation.getPropositionReprise().getEcriture() != null
                        ? regularisation.getPropositionReprise().getEcriture().getId() : null,
                regularisation.getCreatedAt()
        );
    }
}
