package com.example.comptable.comptabilite.parametrage;

/** RESOLVED : un compte utilisable a ete trouve. AMBIGUOUS : plusieurs mappings actifs de meme
 * rang correspondent - jamais tranche arbitrairement. NOT_CONFIGURED : aucun mapping ni role
 * systeme ne repond, l'appelant doit passer l'operation en revue humaine. INVALID : un mapping
 * existe mais le compte qu'il designe est desactive/inutilisable. */
public enum AccountResolutionStatus {
    RESOLVED,
    AMBIGUOUS,
    NOT_CONFIGURED,
    INVALID
}
