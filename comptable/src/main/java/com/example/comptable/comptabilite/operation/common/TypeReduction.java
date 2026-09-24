package com.example.comptable.comptabilite.operation.common;

/** REMISE/RABAIS/RISTOURNE sont nettes directement dans la base HT avant calcul de la TVA (choix
 * structurel documente, pas une regle fiscale verifiee). ESCOMPTE est explicitement NON_SUPPORTE
 * par le moteur : voir AchatRule/VenteRule, aucun calcul n'est tente pour ce type. */
public enum TypeReduction {
    REMISE,
    RABAIS,
    RISTOURNE,
    ESCOMPTE
}
