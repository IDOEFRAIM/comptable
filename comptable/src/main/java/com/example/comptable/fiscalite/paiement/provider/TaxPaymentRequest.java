package com.example.comptable.fiscalite.paiement.provider;

import com.example.comptable.fiscalite.domain.TypeImpot;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Requete d'initiation de paiement, independante du fournisseur.
 *
 * @param referenceInterne id du {@code PaiementFiscal} deja persiste, a inclure dans la reference
 *                          externe simulee (ou transmise au fournisseur reel) pour pouvoir
 *                          retrouver la tentative depuis une confirmation asynchrone
 * @param numeroTelephonePayeur n'est exploite que par les fournisseurs mobile money (Faso Arzeka) ;
 *                              ignore par e-Syntaxe
 */
public record TaxPaymentRequest(
        UUID referenceInterne,
        String numeroContribuable,
        TypeImpot typeImpot,
        String periode,
        BigDecimal montant,
        String devise,
        String numeroTelephonePayeur
) {
}
