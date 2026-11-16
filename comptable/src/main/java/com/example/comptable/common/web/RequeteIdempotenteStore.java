package com.example.comptable.common.web;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Mémoire des requêtes déjà exécutées, par utilisateur et par clé d'idempotence. Chaque appel est validé tout de suite (pas de transaction englobante) : la réservation d'une clé est visible des autres requêtes
 * avant même que la requête ne s'exécute, ce qui bloque aussi deux envois simultanés de la même saisie.
 */
@Component
@RequiredArgsConstructor
public class RequeteIdempotenteStore {

    public enum Etat {
        /** Première fois : la requête doit s'exécuter. */
        NOUVELLE,
        /** Déjà exécutée avec succès : on rejoue la même réponse. */
        REJEU,
        /** Une requête avec cette clé est encore en cours d'exécution. */
        EN_COURS,
        /** La clé a déjà servi pour une autre route ou un autre dossier. */
        CLE_REUTILISEE
    }

    public record Resultat(Etat etat, Integer statut, String corps, String typeContenu) {
        static Resultat de(Etat etat) {
            return new Resultat(etat, null, null, null);
        }
    }

    private final JdbcTemplate jdbc;

    public Resultat reserver(UUID utilisateurId, String cle, String requete) {
        // une requête « en cours » depuis plus de 2 minutes est considérée abandonnée (serveur arrêté en plein traitement) : elle ne bloque pas le renvoi
        jdbc.update("DELETE FROM requete_idempotente WHERE utilisateur_id = ? AND cle = ? AND statut_http IS NULL AND cree_le < now() - interval '2 minutes'", utilisateurId, cle);
        int inserees = jdbc.update("INSERT INTO requete_idempotente (utilisateur_id, cle, requete) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", utilisateurId, cle, requete);
        if (inserees == 1) {
            return Resultat.de(Etat.NOUVELLE);
        }
        Map<String, Object> ligne;
        try {
            ligne = jdbc.queryForMap("SELECT requete, statut_http, corps_reponse, type_contenu FROM requete_idempotente WHERE utilisateur_id = ? AND cle = ?", utilisateurId, cle);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // libérée entre-temps (la requête précédente a échoué) : on retente une réservation
            return reserver(utilisateurId, cle, requete);
        }
        if (!requete.equals(ligne.get("requete"))) {
            return Resultat.de(Etat.CLE_REUTILISEE);
        }
        Number statut = (Number) ligne.get("statut_http");
        if (statut == null) {
            return Resultat.de(Etat.EN_COURS);
        }
        return new Resultat(Etat.REJEU, statut.intValue(), (String) ligne.get("corps_reponse"), (String) ligne.get("type_contenu"));
    }

    public void terminer(UUID utilisateurId, String cle, int statut, String corps, String typeContenu) {
        jdbc.update("UPDATE requete_idempotente SET statut_http = ?, corps_reponse = ?, type_contenu = ? WHERE utilisateur_id = ? AND cle = ?", statut, corps, typeContenu, utilisateurId, cle);
    }

    /** La requête a échoué (refus métier, panne) : rien n'a été créé, la clé est rendue pour que le renvoi puisse s'exécuter. */
    public void liberer(UUID utilisateurId, String cle) {
        jdbc.update("DELETE FROM requete_idempotente WHERE utilisateur_id = ? AND cle = ?", utilisateurId, cle);
    }

    /** Une saisie hors ligne n'est jamais renvoyée au-delà de quelques jours : la mémoire est purgée chaque nuit. */
    @Scheduled(cron = "0 30 3 * * *")
    public void purger() {
        jdbc.update("DELETE FROM requete_idempotente WHERE cree_le < now() - interval '7 days'");
    }
}
