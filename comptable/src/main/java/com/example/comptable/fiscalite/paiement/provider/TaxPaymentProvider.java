package com.example.comptable.fiscalite.paiement.provider;

import com.example.comptable.fiscalite.paiement.domain.PaiementFournisseur;

/**
 * Point d'integration unique par fournisseur de telepaiement fiscal externe. Chaque implementation
 * (e-Syntaxe, Faso Arzeka) encapsule sa propre API et son propre format de reference ; le reste de
 * l'application ({@code PaiementFiscalService}, les controleurs) ne connait que cette interface.
 * Tant que les specifications/API officielles d'un fournisseur ne sont pas disponibles, son
 * implementation tourne en mode simulation (voir la javadoc de chaque classe) : brancher l'API
 * reelle se limite alors a completer sa methode {@code appellerApiReelle}, sans toucher au reste
 * du module.
 */
public interface TaxPaymentProvider {

    PaiementFournisseur fournisseur();

    TaxPaymentInitiationResult initierPaiement(TaxPaymentRequest requete);
}
