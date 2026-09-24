package com.example.comptable.comptabilite.operation.common.domain;

public enum StatutOperationProposal {
    /** Complete : tous les comptes/journal/TVA resolus, lignes equilibrees. Peut etre validee. */
    PROPOSED,
    /** Incomplete : au moins une explication bloquante (compte/journal/regle TVA introuvable,
     * escompte demande...). Ne peut pas etre validee telle quelle. */
    NEEDS_REVIEW,
    /** Validee par un comptable, ecriture reelle creee (ecriture_id renseigne). Etat terminal. */
    APPROVED,
    /** Rejetee par un comptable, motif obligatoire. Etat terminal. */
    REJECTED
}
