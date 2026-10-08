package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Facture d'achat deja comptabilisee qui PEUT porter l'acquisition d'une immobilisation : elle debite le compte d'actif du
 * bien. {@code suffisant} = la valeur restante couvre le bien. Sert de selecteur : l'utilisateur ne saisit jamais d'identifiant.
 */
public record CandidatAcquisitionDto(String sourceType, UUID sourceId, String sourceEvent, String libelle, LocalDate date,
                                     BigDecimal montantDebite, BigDecimal dejaAffecte, BigDecimal capaciteRestante, boolean suffisant) {
}
