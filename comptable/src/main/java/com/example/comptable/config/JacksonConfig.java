package com.example.comptable.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fournit le bean {@code com.fasterxml.jackson.databind.ObjectMapper} (Jackson 2.x classique)
 * qu'utilise notre code (extraction WhatsApp, checkpoints du pipeline agentique...). Spring Boot 4
 * n'auto-configure plus ce type par defaut : son autoconfiguration Jackson native cible
 * {@code tools.jackson.databind.ObjectMapper} (Jackson 3), un package different. Sans ce bean,
 * toute injection de {@code ObjectMapper} classique echoue au demarrage
 * ({@code UnsatisfiedDependencyException}).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
