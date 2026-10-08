package com.example.comptable.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Démarrage refusé en production si une configuration critique est faible, absente ou de développement. Les valeurs ci-dessous sont des exemples de test, jamais des secrets réels. */
class ProductionStartupValidatorTest {

    private MockEnvironment env;
    private JdbcTemplate jdbc;
    private DemoDataGuard demo;
    private Path stockage;

    @BeforeEach
    void preparer() throws Exception {
        stockage = Files.createTempDirectory("pilote-stockage");
        env = new MockEnvironment()
                .withProperty("security.jwt.secret", "Zx9Qm4Lp8Tr2Wv6Bn1Hc5Yd7Kf3Js0Ga-AbCdEf")
                .withProperty("spring.datasource.password", "Mot-De-Passe-Base-Pilote-2026")
                .withProperty("ai-service.outbound-api-key", "cle-sortante-aleatoire-0123456789abcdef")
                .withProperty("ai-service.api-key", "cle-entrante-aleatoire-fedcba9876543210")
                .withProperty("comptable.registration.mode", "PROVISIONING")
                .withProperty("comptable.provisioning.token", "jeton-operateur-aleatoire-0123456789")
                .withProperty("storage.documents.root-dir", stockage.toString())
                .withProperty("collecte.lien-depot.base-url", "https://pilote.example.org/depot");
        env.setActiveProfiles("prod");
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(0L);
        demo = mock(DemoDataGuard.class);
        when(demo.purgerSiVierges()).thenReturn(0);
    }

    private List<String> defauts() {
        return new ProductionStartupValidator(env, jdbc, demo).defauts();
    }

    @Test
    void configurationSaine_aucunDefaut_etLeDemarrageReussit() {
        assertThat(defauts()).isEmpty();
        new ProductionStartupValidator(env, jdbc, demo).afterSingletonsInstantiated();
    }

    @Test
    void laConfigurationInvalideEstRefuseeAvantToutAccesALaBase() {
        env.setProperty("security.jwt.secret", "court");
        // aucun accès à la base : le garde de pré-migration ne dépend que de l'environnement
        assertThatThrownBy(() -> ProductionStartupValidator.avantMigration(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("avant toute migration").hasMessageContaining("JWT_SECRET");
        org.mockito.Mockito.verifyNoInteractions(jdbc, demo);
        env.setProperty("security.jwt.secret", "Zx9Qm4Lp8Tr2Wv6Bn1Hc5Yd7Kf3Js0Ga-AbCdEf");
        ProductionStartupValidator.avantMigration(env);
    }

    @Test
    void secretJwtDeDeveloppement_faibleOuAbsent_refuse() {
        for (String faible : new String[] {"", "court", "changeit-dev-secret-changeit-dev-secret-32B", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}) {
            env.setProperty("security.jwt.secret", faible);
            assertThat(defauts()).as(faible).anyMatch(d -> d.contains("JWT_SECRET"));
        }
    }

    @Test
    void motDePasseDeBaseFaible_refuse() {
        for (String faible : new String[] {"", "comptable", "postgres", "password", "court-12"}) {
            env.setProperty("spring.datasource.password", faible);
            assertThat(defauts()).as(faible).anyMatch(d -> d.contains("SPRING_DATASOURCE_PASSWORD"));
        }
    }

    @Test
    void clesInternesIa_absentesDeDeveloppementOuIdentiques_refusees() {
        env.setProperty("ai-service.outbound-api-key", "devsecret-outbound-2026");
        assertThat(defauts()).anyMatch(d -> d.contains("AI_SERVICE_OUTBOUND_API_KEY"));
        env.setProperty("ai-service.outbound-api-key", "meme-cle-0123456789abcdef0123456789");
        env.setProperty("ai-service.api-key", "meme-cle-0123456789abcdef0123456789");
        assertThat(defauts()).anyMatch(d -> d.contains("distinctes"));
        env.setProperty("ai-service.api-key", "");
        assertThat(defauts()).anyMatch(d -> d.contains("AI_SERVICE_API_KEY"));
    }

    @Test
    void inscriptionOuverte_ouJetonDApprovisionnementManquant_refuse() {
        env.setProperty("comptable.registration.mode", "OPEN");
        assertThat(defauts()).anyMatch(d -> d.contains("OPEN interdit"));
        env.setProperty("comptable.registration.mode", "PROVISIONING");
        env.setProperty("comptable.provisioning.token", "court");
        assertThat(defauts()).anyMatch(d -> d.contains("COMPTABLE_PROVISIONING_TOKEN"));
    }

    @Test
    void stockageInexistantOuUrlPubliqueAbsente_refuses() {
        env.setProperty("storage.documents.root-dir", stockage.resolve("inexistant").toString());
        assertThat(defauts()).anyMatch(d -> d.contains("DOCUMENTS_STORAGE_DIR"));
        env.setProperty("storage.documents.root-dir", stockage.toString());
        env.setProperty("collecte.lien-depot.base-url", "");
        assertThat(defauts()).anyMatch(d -> d.contains("COLLECTE_LIEN_DEPOT_BASE_URL"));
    }

    @Test
    void debogageSqlDansLesJournauxEtActuatorLargementExpose_refuses_enProduction() {
        env.setProperty("logging.level.root", "DEBUG");
        env.setProperty("spring.jpa.show-sql", "true");
        env.setProperty("management.endpoints.web.exposure.include", "health,env,beans");
        assertThat(defauts()).anyMatch(d -> d.contains("logging.level.root")).anyMatch(d -> d.contains("show-sql")).anyMatch(d -> d.contains("exposure"));
    }

    @Test
    void telepaiementActiveSansCleDeWebhook_refuse() {
        env.setProperty("esyntaxe.mode", "reel");
        assertThat(defauts()).anyMatch(d -> d.contains("PAIEMENT_WEBHOOK_API_KEY"));
        env.setProperty("paiement.webhook.api-key", "cle-webhook-aleatoire-0123456789abcdef");
        assertThat(defauts()).noneMatch(d -> d.contains("PAIEMENT_WEBHOOK_API_KEY"));
    }

    @Test
    void cadreFiscalDeTestEnBase_refuse() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(2L);
        assertThat(defauts()).anyMatch(d -> d.contains("cadre(s) fiscal(aux) de test"));
    }

    @Test
    void donneesDeDemonstrationUtilisees_refusees_vierges_retirees() {
        when(demo.purgerSiVierges()).thenReturn(-1);
        assertThat(defauts()).anyMatch(d -> d.contains("données de démonstration"));
        when(demo.purgerSiVierges()).thenReturn(120);
        assertThat(defauts()).as("des données vierges sont retirées, ce n'est pas un défaut").isEmpty();
    }

    @Test
    void lesDefautsSontListesEnUneFois_sansJamaisAfficherUneValeurSecrete() {
        env.setProperty("security.jwt.secret", "SECRET-A-NE-JAMAIS-AFFICHER");
        env.setProperty("spring.datasource.password", "postgres");
        env.setProperty("comptable.registration.mode", "OPEN");
        assertThatThrownBy(() -> new ProductionStartupValidator(env, jdbc, demo).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("démarrage refusé").hasMessageContaining("JWT_SECRET").hasMessageContaining("SPRING_DATASOURCE_PASSWORD").hasMessageContaining("OPEN interdit")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("NE-JAMAIS-AFFICHER").doesNotContain("postgres\n"));
    }

    @Test
    void enStagingLesSecretsSontControlesMaisPasLesReglagesDeJournalisation() {
        env.setActiveProfiles("staging");
        env.setProperty("logging.level.root", "DEBUG");
        assertThat(env.acceptsProfiles(Profiles.of("prod"))).isFalse();
        assertThat(defauts()).isEmpty();
        env.setProperty("security.jwt.secret", "court");
        assertThat(defauts()).anyMatch(d -> d.contains("JWT_SECRET"));
    }
}
