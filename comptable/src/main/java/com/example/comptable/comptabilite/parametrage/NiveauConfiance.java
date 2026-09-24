package com.example.comptable.comptabilite.parametrage;

/** Toute resolution de ce moteur est une correspondance exacte (compte explicite, mapping, ou role
 * systeme) ou n'est pas une resolution du tout - il n'existe aucune logique floue/probabiliste ici
 * (voir la regle absolue de la mission : ne jamais deviner). {@code CERTAIN} n'est donc jamais un
 * score, seulement l'indicateur binaire "resolu par une correspondance exacte" ; volontairement
 * pas de valeur intermediaire pour ne pas laisser croire a un futur scoring flou. */
public enum NiveauConfiance {
    CERTAIN,
    NON_APPLICABLE
}
