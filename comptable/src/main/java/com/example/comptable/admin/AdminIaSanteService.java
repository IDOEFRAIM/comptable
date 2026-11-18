package com.example.comptable.admin;

import com.example.comptable.admin.AdminDtos.EtatService;
import com.example.comptable.admin.AdminDtos.PlafondIaRequest;
import com.example.comptable.admin.AdminDtos.SantePlateforme;
import com.example.comptable.admin.AdminDtos.SanteSauvegarde;
import com.example.comptable.admin.AdminDtos.UsageIa;
import com.example.comptable.admin.AdminDtos.UsageIaCabinet;
import com.example.comptable.admin.AdminDtos.UsageIaOperation;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Supervision de l'IA et de la santé de la plateforme, pour l'administrateur.
 * <p>
 * Honnêteté sur ce qui est mesuré : on compte les appels au microservice IA, leurs échecs, leur latence et les refus de plafond. Les <b>jetons et le coût en argent ne sont pas mesurés</b> :
 * ai-service ne les remonte pas aujourd'hui. Le plafond est donc exprimé en appels par mois, la seule unité réellement contrôlable.
 */
@Service
public class AdminIaSanteService {

    private static final int MOTIF_MIN = 5;

    private final JdbcTemplate jdbc;
    private final AuditTrailService audit;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();
    private final String aiServiceUrl;
    private final String dossierSauvegardes;
    private final long sauvegardeAgeMaxHeures;

    public AdminIaSanteService(JdbcTemplate jdbc, AuditTrailService audit, @Value("${ai-service.base-url:http://localhost:8000}") String aiServiceUrl,
                               @Value("${admin.sauvegarde.dossier:}") String dossierSauvegardes, @Value("${admin.sauvegarde.age-max-heures:36}") long sauvegardeAgeMaxHeures) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.aiServiceUrl = aiServiceUrl;
        this.dossierSauvegardes = dossierSauvegardes;
        this.sauvegardeAgeMaxHeures = sauvegardeAgeMaxHeures;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ usage de l'IA

    @Transactional(readOnly = true)
    public UsageIa usage(UtilisateurPrincipal admin, String mois) {
        AdminUtilisateurService.exigerAdmin(admin);
        YearMonth ym;
        try {
            ym = mois == null || mois.isBlank() ? YearMonth.now() : YearMonth.parse(mois.trim());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Mois invalide : attendu AAAA-MM (ex. 2026-11)");
        }
        OffsetDateTime debut = ym.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime fin = ym.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<UsageIaCabinet> cabinets = jdbc.query("""
                select t.id, t.raison_sociale, t.quota_appels_ia_mois,
                       count(l.id) filter (where l.statut <> 'REFUSE_PLAFOND') appels,
                       count(l.id) filter (where l.statut = 'ECHEC') echecs,
                       count(l.id) filter (where l.statut = 'REFUSE_PLAFOND') refuses,
                       (avg(l.latence_ms) filter (where l.statut = 'OK'))::bigint latence,
                       max(l.created_at) dernier
                  from tenant t
                  left join ai_call_log l on l.tenant_id = t.id and l.created_at >= ? and l.created_at < ?
                 group by t.id, t.raison_sociale, t.quota_appels_ia_mois
                 order by appels desc, t.raison_sociale
                """, (rs, i) -> new UsageIaCabinet(rs.getObject("id", UUID.class), rs.getString("raison_sociale"), rs.getLong("appels"), rs.getLong("echecs"), rs.getLong("refuses"),
                rs.getObject("latence") == null ? null : rs.getLong("latence"), rs.getObject("quota_appels_ia_mois") == null ? null : rs.getInt("quota_appels_ia_mois"),
                rs.getObject("dernier", OffsetDateTime.class)), debut, fin);

        List<UsageIaOperation> operations = jdbc.query("""
                select operation, count(*) filter (where statut <> 'REFUSE_PLAFOND') appels, count(*) filter (where statut = 'ECHEC') echecs,
                       (avg(latence_ms) filter (where statut = 'OK'))::bigint latence
                  from ai_call_log where created_at >= ? and created_at < ?
                 group by operation having count(*) filter (where statut <> 'REFUSE_PLAFOND') > 0 order by appels desc
                """, (rs, i) -> new UsageIaOperation(rs.getString("operation"), rs.getLong("appels"), rs.getLong("echecs"), rs.getObject("latence") == null ? null : rs.getLong("latence")), debut, fin);

        long sansCabinet = jdbc.queryForObject("select count(*) from ai_call_log where tenant_id is null and statut <> 'REFUSE_PLAFOND' and created_at >= ? and created_at < ?", Long.class, debut, fin);
        long appels = cabinets.stream().mapToLong(UsageIaCabinet::appels).sum() + sansCabinet;
        long echecs = cabinets.stream().mapToLong(UsageIaCabinet::echecs).sum()
                + jdbc.queryForObject("select count(*) from ai_call_log where tenant_id is null and statut = 'ECHEC' and created_at >= ? and created_at < ?", Long.class, debut, fin);
        long refuses = cabinets.stream().mapToLong(UsageIaCabinet::refusesPlafond).sum();
        return new UsageIa(ym.toString(), appels, echecs, refuses, sansCabinet, cabinets, operations);
    }

    /** Plafond mensuel d'appels IA d'un cabinet (null = pas de plafond). Motif obligatoire, action tracée. Un plafond sous l'usage du mois bloque les appels suivants, sans rien couper d'autre. */
    @Transactional
    public UsageIaCabinet modifierPlafond(UtilisateurPrincipal admin, UUID cabinetId, PlafondIaRequest req) {
        AdminUtilisateurService.exigerAdmin(admin);
        if (req.motif() == null || req.motif().trim().length() < MOTIF_MIN) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Un motif d'au moins " + MOTIF_MIN + " caractères est obligatoire");
        }
        if (req.plafond() != null && req.plafond() < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le plafond doit être d'au moins 1 appel (laissez vide pour ne pas limiter)");
        }
        List<Map<String, Object>> lignes = jdbc.queryForList("select raison_sociale, quota_appels_ia_mois from tenant where id = ?", cabinetId);
        if (lignes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cabinet introuvable");
        }
        jdbc.update("update tenant set quota_appels_ia_mois = ? where id = ?", req.plafond(), cabinetId);
        Map<String, Object> details = new HashMap<>();
        details.put("motif", req.motif().trim());
        details.put("cabinet", lignes.get(0).get("raison_sociale"));
        details.put("avant", String.valueOf(lignes.get(0).get("quota_appels_ia_mois")));
        details.put("apres", String.valueOf(req.plafond()));
        audit.enregistrerActionHumaine(cabinetId, admin.getId(), "ADMIN_PLAFOND_IA_MODIFIE", "CABINET", cabinetId.toString(), details);
        return usage(admin, null).cabinets().stream().filter((c) -> c.cabinetId().equals(cabinetId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ santé

    public SantePlateforme sante(UtilisateurPrincipal admin) {
        AdminUtilisateurService.exigerAdmin(admin);
        List<EtatService> services = new ArrayList<>();
        services.add(new EtatService("Serveur Warimara", "OK", null, "Répond (vous lisez cette page)."));
        services.add(etatBase());
        services.add(etatIa());
        Runtime rt = Runtime.getRuntime();
        File disque = new File(".");
        long demarre = Duration.between(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()), Instant.now()).toMinutes();
        return new SantePlateforme(OffsetDateTime.now(), services, sauvegarde(), (rt.totalMemory() - rt.freeMemory()) / 1_048_576, rt.maxMemory() / 1_048_576, disque.getUsableSpace() / 1_048_576,
                disque.getTotalSpace() / 1_048_576, versionBase(), demarre);
    }

    private EtatService etatBase() {
        long debut = System.nanoTime();
        try {
            jdbc.queryForObject("select 1", Integer.class);
            long ms = (System.nanoTime() - debut) / 1_000_000;
            return new EtatService("Base de données", ms > 500 ? "DEGRADE" : "OK", ms, ms > 500 ? "Répond lentement." : "Répond normalement.");
        } catch (RuntimeException e) {
            return new EtatService("Base de données", "HORS_SERVICE", null, "Ne répond pas.");
        }
    }

    /** Ping du microservice IA : /health, sinon /openapi.json (anciennes versions sans /health). Aucune donnée n'est envoyée. */
    private EtatService etatIa() {
        long debut = System.nanoTime();
        try {
            int statut = ping("/health");
            if (statut == 404) {
                statut = ping("/openapi.json");
            }
            long ms = (System.nanoTime() - debut) / 1_000_000;
            if (statut >= 200 && statut < 300) {
                return new EtatService("Microservice IA", ms > 2000 ? "DEGRADE" : "OK", ms, ms > 2000 ? "Répond lentement." : "Répond normalement.");
            }
            return new EtatService("Microservice IA", "DEGRADE", ms, "Répond avec une erreur (" + statut + ").");
        } catch (Exception e) {
            return new EtatService("Microservice IA", "HORS_SERVICE", null, "Ne répond pas : les fonctions d'IA sont remplacées par la saisie manuelle.");
        }
    }

    private int ping(String chemin) throws Exception {
        HttpRequest requete = HttpRequest.newBuilder(URI.create(aiServiceUrl.replaceAll("/+$", "") + chemin)).timeout(Duration.ofSeconds(3)).GET().build();
        return http.send(requete, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Dernière sauvegarde : le fichier le plus récent du dossier configuré ({@code admin.sauvegarde.dossier}). Sans dossier configuré, l'état est « NON_SURVEILLEE », jamais « OK ». */
    private SanteSauvegarde sauvegarde() {
        if (dossierSauvegardes == null || dossierSauvegardes.isBlank()) {
            return new SanteSauvegarde("NON_SURVEILLEE", null, null, "Aucun dossier de sauvegarde n'est configuré (admin.sauvegarde.dossier) : Warimara ne peut pas dire si la base est sauvegardée.");
        }
        Path dossier = Path.of(dossierSauvegardes);
        if (!Files.isDirectory(dossier)) {
            return new SanteSauvegarde("ABSENTE", null, null, "Le dossier de sauvegarde configuré est introuvable.");
        }
        try (Stream<Path> fichiers = Files.list(dossier)) {
            Instant derniere = fichiers.filter(Files::isRegularFile).map((f) -> {
                try {
                    return Files.getLastModifiedTime(f).toInstant();
                } catch (Exception e) {
                    return Instant.EPOCH;
                }
            }).max(Instant::compareTo).orElse(null);
            if (derniere == null) {
                return new SanteSauvegarde("ABSENTE", null, null, "Le dossier de sauvegarde est vide.");
            }
            long age = Duration.between(derniere, Instant.now()).toHours();
            boolean ancienne = age > sauvegardeAgeMaxHeures;
            return new SanteSauvegarde(ancienne ? "ANCIENNE" : "OK", derniere.atOffset(ZoneOffset.UTC), age,
                    ancienne ? "La dernière sauvegarde a plus de " + sauvegardeAgeMaxHeures + " heures." : "Sauvegarde récente.");
        } catch (Exception e) {
            return new SanteSauvegarde("ABSENTE", null, null, "Le dossier de sauvegarde n'a pas pu être lu.");
        }
    }

    private String versionBase() {
        try {
            return jdbc.queryForObject("select max(version::int)::text from flyway_schema_history where success and version ~ '^[0-9]+$'", String.class);
        } catch (RuntimeException e) {
            return "inconnue";
        }
    }
}
