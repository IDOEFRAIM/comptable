package com.example.comptable.stock.inventaire.domain;

/** BROUILLON : cree, aucune ligne saisie. EN_COURS : au moins une ligne saisie, comptage en cours.
 * VALIDE : ecarts geles, mouvements de regularisation enregistres, proposition comptable generee
 * (mais pas encore validee par un humain). CLOTURE : la proposition a ete approuvee et l'ecriture
 * reelle existe - etat terminal. */
public enum StatutInventaire {
    BROUILLON,
    EN_COURS,
    VALIDE,
    CLOTURE
}
