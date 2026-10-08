package com.example.comptable.comptabilite.domain;

/**
 * Cycle de vie d'une immobilisation : ACQUISE (enregistree, pas encore utilisee : aucun amortissement)
 * -&gt; EN_SERVICE (mise en service a une date : amortissable) -&gt; SORTIE (cession ou mise au rebut :
 * plus aucune dotation apres l'annee de sortie). Une immobilisation ne revient jamais en arriere.
 */
public enum StatutImmobilisation {
    ACQUISE,
    EN_SERVICE,
    SORTIE
}
