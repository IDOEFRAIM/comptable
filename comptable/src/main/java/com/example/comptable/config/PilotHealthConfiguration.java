package com.example.comptable.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Indicateurs de santé ajoutés pour le pilote (le groupe {@code readiness} les inclut) : le stockage des pièces est inscriptible, aucune migration Flyway n'a échoué. Aucun détail interne n'est exposé
 * (chemins, noms de table, messages d'erreur) : seul l'état UP / DOWN sort par {@code /actuator/health}.
 */
@Configuration
public class PilotHealthConfiguration {

    @Bean
    public HealthIndicator storageHealthIndicator(@Value("${storage.documents.root-dir}") String racine) {
        return () -> {
            Path dossier = Path.of(racine).toAbsolutePath().normalize();
            return Files.isDirectory(dossier) && Files.isWritable(dossier) ? Health.up().build() : Health.down().build();
        };
    }

    @Bean
    public HealthIndicator migrationsHealthIndicator(JdbcTemplate jdbc) {
        return () -> {
            try {
                Integer echecs = jdbc.queryForObject("select count(*) from flyway_schema_history where success = false", Integer.class);
                return echecs != null && echecs == 0 ? Health.up().build() : Health.down().build();
            } catch (RuntimeException e) {
                return Health.down().build();
            }
        };
    }
}
