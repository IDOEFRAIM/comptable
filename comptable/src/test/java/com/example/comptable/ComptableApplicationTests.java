package com.example.comptable;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Correctif P1.5 (section 61) : ce test etait le seul {@code @SpringBootTest} du projet sans
 * {@code spring.docker.compose.skip.in-tests=false} explicite - la propriete est vraie par
 * defaut, donc Docker Compose n'etait jamais sollicite pour la datasource et le contexte
 * echouait a se charger de facon reproductible en execution isolee ("Failed to determine a
 * suitable driver class"), documente depuis P0 comme un flake "intermittent" alors qu'il etait en
 * realite systematique des que ce test tournait seul. Root-cause identifie en construisant
 * {@link com.example.comptable.security.P1_5SecuriteHttpE2ETest}, qui heurtait exactement la
 * meme erreur avant d'ajouter la meme propriete - voir sa Javadoc pour le detail du diagnostic.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class ComptableApplicationTests {

    @Test
    void contextLoads() {
    }

}
