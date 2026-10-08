package com.example.comptable.fiscalite.paiement.domain;

/** Canal de telepaiement direct des obligations fiscales/sociales, propre au Burkina Faso. */
public enum PaiementFournisseur {
    /** e-Syntaxe : plateforme de teledeclaration et telepaiement de la Direction Generale des Impots. */
    E_SYNTAXE,
    /** Faso Arzeka : porte-monnaie electronique de La Poste Burkina Faso (paiements mobile money, ex. CNPS). */
    FASO_ARZEKA
}
