package com.example.comptable.comptabilite.domain;

/** Certaines valeurs ne s'appliquent qu'a un type : ACCEPTE/REFUSE/CONVERTI pour un DEVIS,
 * VALIDEE/PAYEE/ANNULEE pour une FACTURE. BROUILLON et ENVOYE sont communs aux deux. */
public enum StatutFacture {
    BROUILLON,
    ENVOYE,
    ACCEPTE,
    REFUSE,
    CONVERTI,
    VALIDEE,
    PAYEE,
    ANNULEE
}
