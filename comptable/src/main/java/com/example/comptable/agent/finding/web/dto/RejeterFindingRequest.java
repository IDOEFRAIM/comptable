package com.example.comptable.agent.finding.web.dto;

/** Mission P1.9 (section 31) : motif optionnel du rejet, jamais obligatoire (retro-compatible
 * avec un corps vide {@code {}} deja envoye par le frontend existant). */
public record RejeterFindingRequest(String motif) {
}
