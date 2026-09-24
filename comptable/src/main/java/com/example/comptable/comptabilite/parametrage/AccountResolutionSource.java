package com.example.comptable.comptabilite.parametrage;

/** D'ou vient le compte resolu, dans l'ordre de priorite reellement applique (mission Etape 6,
 * section 8). */
public enum AccountResolutionSource {
    EXPLICITE,
    MAPPING_CONTEXTUEL,
    MAPPING_GENERIQUE,
    ROLE_SYSTEME,
    AUCUN
}
