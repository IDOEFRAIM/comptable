package com.example.comptable.comptabilite.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Comptabilisation de l'acquisition : fournisseur et TVA recuperable eventuelle (0 ou absente = sans TVA).
 * {@code confirmerAbsenceDeFacture} : le comptable atteste qu'aucune facture deja comptabilisee ne porte cette
 * acquisition, ce que le systeme ne peut verifier que partiellement (meme compte, meme montant) - sans cette
 * confirmation, une facture candidate bloque la comptabilisation pour eviter un double comptage.
 */
public record ComptabiliserAcquisitionRequest(
        @NotNull UUID fournisseurId,
        @DecimalMin(value = "0.00") BigDecimal montantTva,
        Boolean confirmerAbsenceDeFacture
) {
}
