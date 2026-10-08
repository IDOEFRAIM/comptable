package com.example.comptable.pilot;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.config.DemoDataGuard;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Une base VIERGE monte-t-elle ? Crée une base PostgreSQL vide, exécute toutes les migrations Flyway depuis V1, puis vérifie : migrations validées, aucun compte ni cadre fiscal de test, rôles de base présents,
 * schéma IDENTIQUE à celui de la base de test montée incrémentalement (déterminisme), données de démonstration de V8 retirables par la garde (et seulement vierges). La base temporaire est supprimée ensuite.
 */
class FreshDatabaseMigrationTest extends CollecteTestBase {

    private static final String SIGNATURE_COLONNES = "select table_name || '.' || column_name || ':' || data_type || ':' || is_nullable from information_schema.columns where table_schema = 'public'";
    private static final String SIGNATURE_INDEX = "select indexname from pg_indexes where schemaname = 'public'";
    private static final String SIGNATURE_CONTRAINTES = "select table_name || '.' || constraint_type from information_schema.table_constraints where table_schema = 'public' and constraint_type in ('FOREIGN KEY', 'UNIQUE', 'CHECK') and constraint_name not like '%not_null'";

    @Test
    void baseVierge_toutesLesMigrationsMontent_schemaIdentique_aucunCompteDeTest_demoRetirable() throws Exception {
        HikariDataSource source = jdbc.getDataSource().unwrap(HikariDataSource.class);
        String nom = "fresh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String urlServeur = source.getJdbcUrl();
        String urlFraiche = urlServeur.substring(0, urlServeur.lastIndexOf('/') + 1) + nom + (urlServeur.contains("?") ? urlServeur.substring(urlServeur.indexOf('?')) : "");

        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + nom);
        }
        try {
            Flyway flyway = Flyway.configure().dataSource(urlFraiche, source.getUsername(), source.getPassword()).locations("classpath:db/migration").load();
            MigrateResult resultat = flyway.migrate();
            assertThat(resultat.success).isTrue();
            assertThat(resultat.migrationsExecuted).as("toutes les migrations s'exécutent depuis zéro").isGreaterThanOrEqualTo(69);
            flyway.validate();
            assertThat(flyway.info().pending()).isEmpty();

            JdbcTemplate fraiche = new JdbcTemplate(new DriverManagerDataSource(urlFraiche, source.getUsername(), source.getPassword()));
            assertThat(fraiche.queryForObject("select count(*) from utilisateur", Long.class)).as("aucun compte connu dans une base neuve").isZero();
            assertThat(fraiche.queryForObject("select count(*) from tax_framework", Long.class)).as("aucun cadre fiscal semé").isZero();
            assertThat(fraiche.queryForList("select code from role", String.class)).contains("EXPERT_COMPTABLE", "COLLABORATEUR", "CLIENT");
            assertThat(fraiche.queryForObject("select count(*) from flyway_schema_history where success = false", Long.class)).isZero();

            // déterminisme : le schéma d'une base neuve est celui de la base montée au fil des versions
            for (String requete : List.of(SIGNATURE_COLONNES, SIGNATURE_INDEX, SIGNATURE_CONTRAINTES)) {
                Set<String> neuve = Set.copyOf(fraiche.queryForList(requete, String.class));
                Set<String> courante = Set.copyOf(jdbc.queryForList(requete, String.class));
                Set<String> manquantes = courante.stream().filter(x -> !neuve.contains(x)).collect(Collectors.toSet());
                Set<String> enTrop = neuve.stream().filter(x -> !courante.contains(x)).collect(Collectors.toSet());
                assertThat(manquantes).as("éléments de la base de test absents d'une base neuve").isEmpty();
                assertThat(enTrop).as("éléments d'une base neuve absents de la base de test").isEmpty();
            }

            // données de démonstration de V8 : présentes dans une base neuve, vierges donc retirables ; second passage sans effet
            DemoDataGuard garde = new DemoDataGuard(fraiche);
            assertThat(garde.constater().presentes()).isTrue();
            assertThat(garde.constater().vierges()).isTrue();
            assertThat(garde.purgerSiVierges()).isPositive();
            assertThat(garde.constater().presentes()).isFalse();
            assertThat(fraiche.queryForObject("select count(*) from entreprise", Long.class)).isZero();
            assertThat(fraiche.queryForObject("select count(*) from tenant", Long.class)).isZero();
            assertThat(garde.purgerSiVierges()).isZero();

        } finally {
            try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
                s.execute("drop database if exists " + nom + " with (force)");
            }
        }
    }

    @Test
    void donneesDeDemonstrationUtilisees_neSontJamaisPurgees() throws Exception {
        HikariDataSource source = jdbc.getDataSource().unwrap(HikariDataSource.class);
        String nom = "fresh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String urlServeur = source.getJdbcUrl();
        String urlFraiche = urlServeur.substring(0, urlServeur.lastIndexOf('/') + 1) + nom + (urlServeur.contains("?") ? urlServeur.substring(urlServeur.indexOf('?')) : "");
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + nom);
        }
        try {
            Flyway.configure().dataSource(urlFraiche, source.getUsername(), source.getPassword()).locations("classpath:db/migration").load().migrate();
            JdbcTemplate fraiche = new JdbcTemplate(new DriverManagerDataSource(urlFraiche, source.getUsername(), source.getPassword()));
            // quelqu'un a rattaché un utilisateur au cabinet de démonstration : la garde refuse de rien supprimer
            fraiche.update("insert into utilisateur (id, tenant_id, email, mot_de_passe, nom, prenom, statut, created_at) select gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'reel@cabinet.test', 'x', 'Reel', 'Compte', 'ACTIF', now()");
            DemoDataGuard garde = new DemoDataGuard(fraiche);
            assertThat(garde.constater().vierges()).isFalse();
            assertThat(garde.purgerSiVierges()).isEqualTo(-1);
            assertThat(fraiche.queryForObject("select count(*) from entreprise where id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'", Long.class)).isEqualTo(1L);
        } finally {
            try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
                s.execute("drop database if exists " + nom + " with (force)");
            }
        }
    }
}
