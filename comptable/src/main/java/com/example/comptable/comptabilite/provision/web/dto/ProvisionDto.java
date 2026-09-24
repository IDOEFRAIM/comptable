package com.example.comptable.comptabilite.provision.web.dto;

import com.example.comptable.comptabilite.provision.domain.Provision;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProvisionDto(
        UUID id,
        UUID exerciceId,
        String nature,
        String objet,
        BigDecimal montantPrecedent,
        BigDecimal montantNouveau,
        String justification,
        String typeMouvement,
        UUID compteDotationId,
        UUID compteProvisionId,
        UUID compteRepriseId,
        String statut,
        UUID propositionId,
        UUID ecritureId,
        OffsetDateTime createdAt
) {
    public static ProvisionDto from(Provision provision) {
        return new ProvisionDto(
                provision.getId(),
                provision.getExercice().getId(),
                provision.getNature(),
                provision.getObjet(),
                provision.getMontantPrecedent(),
                provision.getMontantNouveau(),
                provision.getJustification(),
                provision.getTypeMouvement().name(),
                provision.getCompteDotation().getId(),
                provision.getCompteProvision().getId(),
                provision.getCompteReprise().getId(),
                provision.getStatut().name(),
                provision.getProposition() != null ? provision.getProposition().getId() : null,
                provision.getProposition() != null && provision.getProposition().getEcriture() != null
                        ? provision.getProposition().getEcriture().getId() : null,
                provision.getCreatedAt()
        );
    }
}
