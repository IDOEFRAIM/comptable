package com.example.comptable.fiscalite.paiement.web.dto;

import com.example.comptable.fiscalite.paiement.domain.PaiementFiscal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaiementFiscalDto(
        UUID id,
        String fournisseur,
        String statut,
        BigDecimal montant,
        String numeroTelephonePayeur,
        String referenceExterne,
        String messageStatut,
        OffsetDateTime dateInitiation,
        OffsetDateTime dateConfirmation
) {
    public static PaiementFiscalDto from(PaiementFiscal p) {
        return new PaiementFiscalDto(
                p.getId(),
                p.getFournisseur().name(),
                p.getStatut().name(),
                p.getMontant(),
                p.getNumeroTelephonePayeur(),
                p.getReferenceExterne(),
                p.getMessageStatut(),
                p.getDateInitiation(),
                p.getDateConfirmation()
        );
    }
}
