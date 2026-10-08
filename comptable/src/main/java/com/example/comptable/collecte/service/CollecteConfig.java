package com.example.comptable.collecte.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Parametres de la collecte. Tout est configurable : aucune cadence n'est une regle reglementaire.
 * <ul>
 *   <li>{@code collecte.relance.delais-jours} : delais entre l'envoi et chaque relance client (defaut {@code 3,7} : relance 1
 *       trois jours apres la demande, relance 2 sept jours apres la relance 1, puis intervention humaine) ;</li>
 *   <li>{@code collecte.relance.max-messages-par-jour} : plafond de messages par destinataire et par jour pour les RELANCES ;</li>
 *   <li>{@code collecte.retry.*} : retry technique (tentatives, delais entre tentatives) ;</li>
 *   <li>{@code collecte.expiration-jours} : une demande sans reponse apres ce delai expire ;</li>
 *   <li>{@code collecte.destinataires} : {@code PREMIER} (un interlocuteur) ou {@code TOUS} les clients rattaches ayant un telephone.</li>
 * </ul>
 */
@Component
public class CollecteConfig {

    private final List<Integer> delaisRelanceJours;
    private final int maxMessagesParJour;
    private final int maxTentatives;
    private final List<Integer> backoffMinutes;
    private final int expirationJours;
    private final int tokenValiditeJours;
    private final String lienDepotBaseUrl;
    private final boolean tousLesDestinataires;
    private final String templateDemande;
    private final String templateRelance;
    private final int taillePaquetEnvoi;
    private final boolean envoiImmediat;

    public CollecteConfig(@Value("${collecte.relance.delais-jours:3,7}") String delais,
                          @Value("${collecte.relance.max-messages-par-jour:2}") int maxMessagesParJour,
                          @Value("${collecte.retry.max-tentatives:4}") int maxTentatives,
                          @Value("${collecte.retry.backoff-minutes:5,30,120}") String backoff,
                          @Value("${collecte.expiration-jours:60}") int expirationJours,
                          @Value("${collecte.lien-depot.validite-jours:45}") int tokenValiditeJours,
                          @Value("${collecte.lien-depot.base-url:}") String lienDepotBaseUrl,
                          @Value("${collecte.destinataires:PREMIER}") String destinataires,
                          @Value("${collecte.whatsapp.template-demande:relance_pieces_comptables}") String templateDemande,
                          @Value("${collecte.whatsapp.template-relance:relance_pieces_comptables}") String templateRelance,
                          @Value("${collecte.envoi.taille-paquet:25}") int taillePaquetEnvoi,
                          @Value("${collecte.envoi.immediat:false}") boolean envoiImmediat) {
        this.delaisRelanceJours = liste(delais);
        this.maxMessagesParJour = maxMessagesParJour;
        this.maxTentatives = Math.max(1, maxTentatives);
        this.backoffMinutes = liste(backoff);
        this.expirationJours = expirationJours;
        this.tokenValiditeJours = tokenValiditeJours;
        this.lienDepotBaseUrl = lienDepotBaseUrl;
        this.tousLesDestinataires = "TOUS".equalsIgnoreCase(destinataires);
        this.templateDemande = templateDemande;
        this.templateRelance = templateRelance;
        this.taillePaquetEnvoi = taillePaquetEnvoi;
        this.envoiImmediat = envoiImmediat;
    }

    private static List<Integer> liste(String texte) {
        return Arrays.stream(texte.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).toList();
    }

    public List<Integer> delaisRelanceJours() {
        return delaisRelanceJours;
    }

    /** Nombre maximal de relances client avant intervention humaine. */
    public int maxRelances() {
        return delaisRelanceJours.size();
    }

    /** Delai (jours) avant la prochaine relance quand {@code relancesDejaFaites} ont deja ete envoyees ; vide = intervention humaine. */
    public java.util.OptionalInt delaiAvantRelance(int relancesDejaFaites) {
        return relancesDejaFaites < delaisRelanceJours.size()
                ? java.util.OptionalInt.of(delaisRelanceJours.get(relancesDejaFaites)) : java.util.OptionalInt.empty();
    }

    public int maxMessagesParJour() {
        return maxMessagesParJour;
    }

    public int maxTentatives() {
        return maxTentatives;
    }

    /** Delai avant la tentative suivante (la 1re tentative a deja eu lieu quand {@code tentativesFaites} vaut 1). */
    public int backoffMinutes(int tentativesFaites) {
        if (backoffMinutes.isEmpty()) {
            return 5;
        }
        return backoffMinutes.get(Math.min(Math.max(tentativesFaites - 1, 0), backoffMinutes.size() - 1));
    }

    public int expirationJours() {
        return expirationJours;
    }

    public int tokenValiditeJours() {
        return tokenValiditeJours;
    }

    public String lienDepotBaseUrl() {
        return lienDepotBaseUrl;
    }

    public boolean tousLesDestinataires() {
        return tousLesDestinataires;
    }

    public String templateDemande() {
        return templateDemande;
    }

    public String templateRelance() {
        return templateRelance;
    }

    /** Declencher un passage d'envoi juste apres le commit (sinon le worker planifie s'en charge). */
    public boolean envoiImmediat() {
        return envoiImmediat;
    }

    public int taillePaquetEnvoi() {
        return taillePaquetEnvoi;
    }
}
