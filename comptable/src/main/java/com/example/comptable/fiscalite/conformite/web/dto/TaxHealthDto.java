package com.example.comptable.fiscalite.conformite.web.dto;

/** Mission P1 (section 33) : "Tax Health" - simple presentation du score du domaine Fiscalite
 * deja calcule par {@code DossierComptableService} (mission P0). Pas un second algorithme de
 * score : meme penalites, meme source de Finding. */
public record TaxHealthDto(int score) {
}
