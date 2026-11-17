package com.example.comptable.comptabilite.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Numéros de documents (devis, factures, avoirs) sans doublon ni trou provoqué par une course.
 * <p>
 * Avant : « nombre de documents + 1 ». Deux envois simultanés (typiquement deux appareils qui se synchronisent
 * au retour d'Internet) lisaient le même nombre, et le second échouait sur la contrainte d'unicité.
 * Maintenant : un verrou de transaction PostgreSQL propre au dossier et au préfixe sérialise les attributions,
 * et le numéro suivant est le plus grand numéro existant de l'année + 1 (robuste aux suppressions de brouillons).
 * Le verrou est relâché à la fin de la transaction qui crée le document, donc le numéro lu est toujours visible
 * pour la suivante.
 */
@Service
@RequiredArgsConstructor
public class NumerotationDocumentService {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public String prochain(UUID entrepriseId, String prefixe, String table) {
        if (!"facture".equals(table) && !"facture_avoir".equals(table)) {
            throw new IllegalArgumentException("Table de numérotation inconnue : " + table);
        }
        int annee = LocalDate.now().getYear();
        String debut = "%s-%d-".formatted(prefixe, annee);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, entrepriseId + "|" + debut);
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(substring(numero FROM '[0-9]+$') AS bigint)), 0) FROM " + table
                        + " WHERE entreprise_id = ? AND numero LIKE ?",
                Long.class, entrepriseId, debut + "%");
        return "%s%04d".formatted(debut, (max == null ? 0 : max) + 1);
    }
}
