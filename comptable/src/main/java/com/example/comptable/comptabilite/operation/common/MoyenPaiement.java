package com.example.comptable.comptabilite.operation.common;

/** Moyen de paiement d'un reglement - determine le compte de tresorerie resolu (voir
 * ReglementRule) : ESPECES -&gt; TRESORERIE_CAISSE, tout le reste -&gt; TRESORERIE_BANQUE
 * (simplification documentee : CARTE/MOBILE_MONEY n'ont pas de compte dedie distinct dans
 * cette premiere version). */
public enum MoyenPaiement {
    ESPECES,
    CHEQUE,
    VIREMENT,
    CARTE,
    MOBILE_MONEY,
    AUTRE
}
