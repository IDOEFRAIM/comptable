package com.example.comptable.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** En prod / staging, la configuration sensible est validée AVANT que Flyway ne modifie la base : un démarrage refusé ne laisse aucune migration appliquée. */
@Configuration
@Profile({"prod", "staging"})
class ConfigurationAvantMigration {

    @Bean
    FlywayMigrationStrategy validerConfigurationPuisMigrer(Environment env) {
        return flyway -> {
            ProductionStartupValidator.avantMigration(env);
            flyway.migrate();
        };
    }
}
