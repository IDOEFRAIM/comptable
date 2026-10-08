package com.example.comptable.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Horloge UNIQUE de l'application : les services dont le comportement dépend du jour (échéances du cabinet, finalisation fiscale, tâche de relance) l'injectent au lieu d'appeler
 * {@code LocalDate.now()} directement. En production : l'horloge système ; en test : {@code Clock.fixed(...)} fournie par une configuration de test (aucune autre abstraction temporelle n'existe).
 */
@Configuration
public class TimeConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
