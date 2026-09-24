package com.example.comptable.comptabilite.operation.achat;

/** Nature d'un achat - determine la coherence attendue entre la nature et la classe SYSCOHADA du
 * compte de charge choisi (voir AchatRule) : IMMOBILISATION exige un compte de classe 2, toute
 * autre nature exige un compte de classe 6. Empeche de generer un achat classique pour une
 * immobilisation (et inversement). */
public enum NatureAchat {
    MARCHANDISE,
    MATIERE_PREMIERE,
    /** Fourniture consommee (non revendue, non stockee en permanence) - classe 6 comme SERVICE/CHARGE. */
    CONSOMMABLE,
    SERVICE,
    CHARGE,
    /** Frais rattache a un achat (transport, port...) - modelise comme une ligne a part entiere,
     * pas comme un sous-attribut d'une autre ligne (voir AchatRule.construireMultiLigne). */
    FRAIS_ACCESSOIRE,
    IMMOBILISATION,
    AUTRE
}
