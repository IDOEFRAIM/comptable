package com.example.comptable.comptabilite.web.dto;

import java.util.List;
import java.util.UUID;

/** Noeud de l'arbre du PCE d'une entreprise (regroupement en Java du resultat plat de
 * {@code listerComptes}, volume par entreprise trop faible pour justifier une requete SQL
 * recursive). */
public record CompteComptableArbreDto(
        UUID id,
        String numero,
        String intitule,
        short niveau,
        boolean actif,
        boolean origineReference,
        List<CompteComptableArbreDto> enfants
) {
}
