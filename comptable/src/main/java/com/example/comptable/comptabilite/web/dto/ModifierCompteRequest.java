package com.example.comptable.comptabilite.web.dto;

/** Edition d'un compte du PCE : uniquement le libelle local et/ou l'activation - jamais le
 * numero, le parent, ni la reference vers le referentiel global. Champs nullable = non modifie. */
public record ModifierCompteRequest(
        String intitule,
        Boolean actif
) {
}
