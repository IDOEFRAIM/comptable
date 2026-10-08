package com.example.comptable.cabinet.support;

import com.example.comptable.security.UtilisateurPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Diagnostic de support, SANS SQL : répond à « pourquoi ce dossier ne fonctionne pas ? » pour le cabinet connecté (expert-comptable) : envois en échec, pièces en erreur, constats bloquants, comptes verrouillés,
 * dernière activité, tâches planifiées, dernière migration. Ne montre aucune trace, aucun secret, aucun contenu de pièce ni de message ; les compteurs d'instance (5xx, pool de connexions) ne concernent que ce serveur.
 */
@Service
public class SupportDiagnosticService {

    public record Probleme(String code, String libelle, long nombre, String action) {
    }

    public record DossierEnAlerte(UUID entrepriseId, String entreprise, long constatsBloquants, long piecesEnErreur, long envoisEnEchec, OffsetDateTime derniereActivite) {
    }

    public record Instance(long reponses5xx, Double connexionsActives, Double connexionsMax, boolean sandboxEnvoi, boolean envoiActif, boolean relanceActive, String derniereMigration) {
    }

    public record Diagnostic(OffsetDateTime genereLe, List<Probleme> problemes, List<DossierEnAlerte> dossiers, List<String> comptesVerrouilles, OffsetDateTime derniereActiviteCabinet, Instance instance) {
    }

    private final JdbcTemplate jdbc;
    private final MeterRegistry metriques;
    private final Clock clock;
    private final boolean envoiActif;
    private final boolean relanceActive;
    private final String sandbox;

    public SupportDiagnosticService(JdbcTemplate jdbc, MeterRegistry metriques, Clock clock, @Value("${collecte.envoi.actif:false}") boolean envoiActif,
                                    @Value("${collecte.relance.actif:false}") boolean relanceActive, @Value("${collecte.sandbox.allowlist:}") String sandbox) {
        this.jdbc = jdbc;
        this.metriques = metriques;
        this.clock = clock;
        this.envoiActif = envoiActif;
        this.relanceActive = relanceActive;
        this.sandbox = sandbox;
    }

    @Transactional(readOnly = true)
    public Diagnostic diagnostiquer(UtilisateurPrincipal principal) {
        if (principal.getTenantId() == null || principal.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_EXPERT_COMPTABLE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Le diagnostic est réservé à l'expert-comptable du cabinet");
        }
        UUID tenant = principal.getTenantId();
        OffsetDateTime maintenant = clock.instant().atOffset(java.time.ZoneOffset.UTC);

        long envoisEchec = n("select count(*) from outbound_message m join entreprise e on e.id = m.entreprise_id where e.tenant_id = ? and m.status = 'FAILED'", tenant);
        long envoisBloques = n("select count(*) from outbound_message m join entreprise e on e.id = m.entreprise_id where e.tenant_id = ? and m.status in ('PENDING', 'SENDING') and coalesce(m.next_attempt_at, m.created_at) < ?", tenant, maintenant.minusHours(1));
        long demandesEchec = n("select count(*) from demande_piece d join entreprise e on e.id = d.entreprise_id where e.tenant_id = ? and d.statut = 'ECHEC_ENVOI'", tenant);
        long piecesErreur = n("select count(*) from document d join entreprise e on e.id = d.entreprise_id where e.tenant_id = ? and d.statut = 'ERREUR'", tenant);
        long bloquants = n("select count(*) from finding f join entreprise e on e.id = f.entreprise_id where e.tenant_id = ? and e.statut = 'ACTIF' and f.status in ('OPEN', 'IN_PROGRESS') and (f.severity = 'CRITICAL' or f.control_severity = 'BLOCKING')", tenant);
        List<String> verrouilles = jdbc.queryForList("select trim(prenom || ' ' || nom) from utilisateur where tenant_id = ? and locked_until is not null and locked_until > ? order by nom", String.class, tenant, maintenant);

        List<Probleme> problemes = List.of(
                new Probleme("ENVOIS_EN_ECHEC", "Messages au client en échec définitif", envoisEchec, "Vérifier le contact du client (numéro) puis relancer la demande depuis la collecte de pièces."),
                new Probleme("ENVOIS_BLOQUES", "Messages en attente depuis plus d'une heure", envoisBloques, "Vérifier que l'envoi est actif et que le fournisseur de messagerie répond (voir « Tâches » ci-dessous)."),
                new Probleme("DEMANDES_ECHEC_ENVOI", "Demandes de pièces non envoyées", demandesEchec, "Ouvrir la demande : le motif du blocage y est indiqué."),
                new Probleme("PIECES_EN_ERREUR", "Pièces dont la lecture a échoué", piecesErreur, "Rouvrir la pièce dans le Document Hub et la retraiter, ou la ressaisir."),
                new Probleme("CONSTATS_BLOQUANTS", "Constats bloquants ouverts", bloquants, "Traiter les constats dans l'Inbox de révision."),
                new Probleme("COMPTES_VERROUILLES", "Comptes verrouillés après échecs de connexion", verrouilles.size(), "Attendre la fin du verrouillage ou réinitialiser le mot de passe du membre (Équipe)."));

        List<DossierEnAlerte> dossiers = jdbc.query("""
                select e.id, e.raison_sociale,
                       (select count(*) from finding f where f.entreprise_id = e.id and f.status in ('OPEN', 'IN_PROGRESS') and (f.severity = 'CRITICAL' or f.control_severity = 'BLOCKING')) bloquants,
                       (select count(*) from document d where d.entreprise_id = e.id and d.statut = 'ERREUR') pieces,
                       (select count(*) from outbound_message m where m.entreprise_id = e.id and m.status = 'FAILED') envois,
                       (select max(ec.created_at) from ecriture_comptable ec where ec.entreprise_id = e.id) activite
                from entreprise e where e.tenant_id = ? and e.statut = 'ACTIF'
                order by 3 desc, 4 desc, 5 desc, e.raison_sociale limit 20
                """, (rs, i) -> new DossierEnAlerte(rs.getObject("id", UUID.class), rs.getString("raison_sociale"), rs.getLong("bloquants"), rs.getLong("pieces"), rs.getLong("envois"),
                rs.getTimestamp("activite") == null ? null : rs.getTimestamp("activite").toInstant().atOffset(java.time.ZoneOffset.UTC)), tenant).stream()
                .filter(d -> d.constatsBloquants() + d.piecesEnErreur() + d.envoisEnEchec() > 0).toList();

        java.sql.Timestamp derniere = jdbc.queryForObject("select max(created_at) from audit_log where tenant_id = ?", java.sql.Timestamp.class, tenant);
        String migration = jdbc.queryForObject("select version from flyway_schema_history where success = true and version is not null order by installed_rank desc limit 1", String.class);
        Instance instance = new Instance((long) compteur("comptable.http.reponses.5xx"), jauge("hikaricp.connections.active"), jauge("hikaricp.connections.max"), sandbox != null && !sandbox.isBlank(),
                envoiActif, relanceActive, migration);
        return new Diagnostic(maintenant, problemes, dossiers, verrouilles, derniere == null ? null : derniere.toInstant().atOffset(java.time.ZoneOffset.UTC), instance);
    }

    private long n(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private double compteur(String nom) {
        var c = Search.in(metriques).name(nom).counter();
        return c == null ? 0 : c.count();
    }

    private Double jauge(String nom) {
        var g = Search.in(metriques).name(nom).gauge();
        return g == null ? null : g.value();
    }
}
