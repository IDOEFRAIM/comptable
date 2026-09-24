package com.example.comptable.comptabilite.web.dto;

/** Compte du referentiel non importe lors d'un import, avec la raison (jamais un echec silencieux). */
public record CompteIgnoreDto(String numero, String raison) {
}
