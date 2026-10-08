package com.example.comptable.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Démarrage REFUSÉ en profil {@code prod} (et {@code staging}, pour les contrôles de secrets) si une configuration critique est absente, faible ou reste à sa valeur de développement. Tous les défauts sont listés
 * en une fois, sans jamais afficher une valeur secrète. Contrôles : secret JWT, mot de passe de base, clés internes IA, mode d'inscription et jeton d'approvisionnement, stockage des pièces, URL publique de dépôt,
 * journalisation en débogage, mode simulation des paiements non déclaré, cadres fiscaux de test (TEST_ONLY), données de démonstration non vierges. Les données de démonstration vierges de la migration V8 sont retirées.
 */
@Component
@Profile({"prod", "staging"})
public class ProductionStartupValidator implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(ProductionStartupValidator.class);
    private static final Set<String> MOTS_DE_PASSE_FAIBLES = Set.of("", "comptable", "password", "postgres", "admin", "changeme", "secret", "test", "local_dev_pg_pwd_9f3c1a7e2b");

    private final Environment env;
    private final JdbcTemplate jdbc;
    private final DemoDataGuard demoDataGuard;

    public ProductionStartupValidator(Environment env, JdbcTemplate jdbc, DemoDataGuard demoDataGuard) {
        this.env = env;
        this.jdbc = jdbc;
        this.demoDataGuard = demoDataGuard;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> defauts = defauts();
        if (!defauts.isEmpty()) {
            throw new IllegalStateException("Configuration de production invalide — démarrage refusé :\n - " + String.join("\n - ", defauts));
        }
        LOG.info("Configuration de production validée.");
    }

    List<String> defauts() {
        List<String> d = defautsConfiguration(env);
        d.addAll(defautsBase());
        return d;
    }

    /** Contrôles qui ne touchent PAS la base : exécutés AVANT toute migration (voir {@link #avantMigration}), puis rejoués ici. */
    static List<String> defautsConfiguration(Environment env) {
        List<String> d = new ArrayList<>();
        boolean prod = env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));

        String jwt = env.getProperty("security.jwt.secret", "");
        if (jwt.length() < 32 || jwt.toLowerCase(Locale.ROOT).contains("changeit") || jwt.toLowerCase(Locale.ROOT).contains("dev-secret") || jwt.chars().distinct().count() < 12) {
            d.add("JWT_SECRET absent, trop court (32 caractères minimum), peu varié ou égal à la valeur de développement");
        }
        String mdp = env.getProperty("spring.datasource.password", "");
        if (mdp.length() < 12 || MOTS_DE_PASSE_FAIBLES.contains(mdp.toLowerCase(Locale.ROOT))) {
            d.add("Mot de passe de la base (SPRING_DATASOURCE_PASSWORD) absent, trop court (12 caractères minimum) ou faible");
        }
        String sortante = env.getProperty("ai-service.outbound-api-key", "");
        if (sortante.isBlank() || sortante.startsWith("devsecret")) {
            d.add("AI_SERVICE_OUTBOUND_API_KEY absente ou égale à la valeur de développement");
        }
        String entrante = env.getProperty("ai-service.api-key", "");
        if (entrante.isBlank() || entrante.equals(sortante)) {
            d.add("AI_SERVICE_API_KEY (clé entrante) absente, ou identique à la clé sortante : les deux clés doivent être distinctes");
        }
        String mode = env.getProperty("comptable.registration.mode", "OPEN");
        if ("OPEN".equalsIgnoreCase(mode)) {
            d.add("COMPTABLE_REGISTRATION_MODE=OPEN interdit : la création de cabinet doit être réservée à l'opérateur (PROVISIONING)");
        } else if (env.getProperty("comptable.provisioning.token", "").length() < 24) {
            d.add("COMPTABLE_PROVISIONING_TOKEN absent ou trop court (24 caractères minimum) alors que l'inscription est en mode " + mode);
        }
        String stockage = env.getProperty("storage.documents.root-dir", "");
        Path racine = stockage.isBlank() ? null : Path.of(stockage).toAbsolutePath().normalize();
        if (racine == null || !Files.isDirectory(racine) || !Files.isWritable(racine)) {
            d.add("DOCUMENTS_STORAGE_DIR absent, inexistant ou non inscriptible");
        }
        if (env.getProperty("collecte.lien-depot.base-url", "").isBlank()) {
            d.add("COLLECTE_LIEN_DEPOT_BASE_URL (URL publique des liens de dépôt) absente");
        }
        if (prod) {
            for (String cle : List.of("logging.level.root", "logging.level.com.example.comptable")) {
                String niveau = env.getProperty(cle, "INFO").toUpperCase(Locale.ROOT);
                if (niveau.equals("DEBUG") || niveau.equals("TRACE")) {
                    d.add(cle + "=" + niveau + " interdit en production");
                }
            }
            if (Boolean.parseBoolean(env.getProperty("spring.jpa.show-sql", "false"))) {
                d.add("spring.jpa.show-sql=true interdit en production (données dans les journaux)");
            }
            if (!env.getProperty("management.endpoints.web.exposure.include", "health").matches("^(health|info|health,info|info,health)$")) {
                d.add("management.endpoints.web.exposure.include expose plus que health/info");
            }
            if (!"simulation".equalsIgnoreCase(env.getProperty("esyntaxe.mode", "simulation")) || !"simulation".equalsIgnoreCase(env.getProperty("fasoarzeka.mode", "simulation"))) {
                if (env.getProperty("paiement.webhook.api-key", "").length() < 24) {
                    d.add("PAIEMENT_WEBHOOK_API_KEY absente alors qu'un télépaiement est activé");
                }
            }
        }
        return d;
    }

    /** Contrôles qui lisent la base : possibles seulement une fois le schéma migré. */
    private List<String> defautsBase() {
        List<String> d = new ArrayList<>();
        long test = nombre("select count(*) from tax_framework where upper(version) like 'TEST%' or upper(version) like 'E2E%' or upper(version) like 'BENCH%'");
        if (test > 0) {
            d.add(test + " cadre(s) fiscal(aux) de test (TEST_ONLY / E2E / BENCH) présent(s) en base : ils ne doivent jamais exister en production");
        }
        int purge = demoDataGuard.purgerSiVierges();
        if (purge < 0) {
            d.add("Les données de démonstration de la migration V8 (cabinets 1111…/2222…) ont été utilisées : nettoyez-les ou restaurez une base propre");
        }
        return d;
    }

    /**
     * Garde-fou posé AVANT la migration Flyway : une configuration manifestement invalide (secret faible, inscription ouverte, stockage absent…) est refusée sans avoir modifié la base.
     * Les contrôles qui exigent la base (fixtures de test, données de démonstration) restent exécutés après la migration.
     */
    static void avantMigration(Environment env) {
        List<String> defauts = defautsConfiguration(env);
        if (!defauts.isEmpty()) {
            throw new IllegalStateException("Configuration de production invalide - démarrage refusé (avant toute migration de la base) :" + "\n - " + String.join("\n - ", defauts));
        }
    }

    private long nombre(String sql) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
