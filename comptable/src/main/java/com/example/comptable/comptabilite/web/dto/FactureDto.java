package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.Facture;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FactureDto(
        UUID id,
        String type,
        String numero,
        UUID tiersId,
        String tiersNom,
        LocalDate dateEmission,
        LocalDate dateEcheance,
        String statut,
        String objet,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        UUID devisOrigineId,
        UUID ecritureId,
        List<LigneFactureDto> lignes,
        /** Statut de paiement CALCULE depuis les allocations de reglements (NON_PAYEE, PARTIELLEMENT_PAYEE, PAYEE) ; null tant que la facture n'est pas comptabilisee. */
        String statutPaiement,
        BigDecimal montantRegle,
        BigDecimal resteAPayer
) {
    public static FactureDto from(Facture facture) {
        return new FactureDto(
                facture.getId(), facture.getType().name(), facture.getNumero(),
                facture.getTiers().getId(), facture.getTiers().getNom(),
                facture.getDateEmission(), facture.getDateEcheance(), facture.getStatut().name(), facture.getObjet(),
                facture.montantHt(), facture.montantTva(), facture.montantTtc(),
                facture.getDevisOrigineId(), facture.getEcritureId(),
                facture.getLignes().stream().map(LigneFactureDto::from).toList(),
                null, null, null
        );
    }

    public FactureDto avecPaiement(String statutPaiement, BigDecimal montantRegle, BigDecimal resteAPayer) {
        return new FactureDto(id, type, numero, tiersId, tiersNom, dateEmission, dateEcheance, statut, objet, montantHt, montantTva, montantTtc,
                devisOrigineId, ecritureId, lignes, statutPaiement, montantRegle, resteAPayer);
    }
}
