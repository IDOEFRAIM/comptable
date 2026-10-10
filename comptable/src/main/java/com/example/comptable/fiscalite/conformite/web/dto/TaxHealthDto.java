package com.example.comptable.fiscalite.conformite.web.dto;

/**
 * État de la conformité fiscale. {@code evalue = false} tant qu'aucun cadre fiscal TVA VÉRIFIÉ ne s'applique à l'entreprise (cas du Burkina Faso aujourd'hui) : le produit ne peut alors pas juger la conformité et
 * ne l'affiche pas comme bonne. Sinon, {@code anomaliesOuvertes} est le nombre réel de constats fiscaux ouverts (jamais un score à barème fixe).
 */
public record TaxHealthDto(boolean evalue, String motif, int anomaliesOuvertes) {
}
