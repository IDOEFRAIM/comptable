package com.example.comptable.cabinet.workspace;

import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.EcheanceRequest;
import com.example.comptable.collecte.CollecteTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les échéances du cabinet se classent selon l'horloge INJECTÉE : avec une horloge fixe (2031-01-10) le résultat ne dépend plus du jour réel où le test s'exécute. C'est l'unique abstraction temporelle
 * de l'application ({@code TimeConfig} fournit l'horloge système en production).
 */
@Import(CabinetClockTest.HorlogeFixe.class)
class CabinetClockTest extends CollecteTestBase {

    @TestConfiguration
    static class HorlogeFixe {
        @Bean
        @Primary
        Clock horlogeDeTest() {
            return Clock.fixed(Instant.parse("2031-01-10T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired CabinetWorkspaceService workspace;
    @Autowired Clock clock;

    @Test
    void echeances_classeesSelonLHorlogeInjectee_independammentDuJourReel() {
        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2031, 1, 10));
        Dossier d = nouveauDossier(false);
        var auteur = d.expert();
        assertThat(creer(d, auteur, LocalDate.of(2031, 1, 5)).periode()).isEqualTo("EN_RETARD");
        assertThat(creer(d, auteur, LocalDate.of(2031, 1, 10)).periode()).isEqualTo("AUJOURDHUI");
        assertThat(creer(d, auteur, LocalDate.of(2031, 1, 14)).periode()).isEqualTo("CETTE_SEMAINE");
        assertThat(creer(d, auteur, LocalDate.of(2031, 2, 20)).periode()).isEqualTo("A_VENIR");
        var bilan = workspace.echeances(auteur, "TOUTES", false);
        assertThat(bilan.items()).filteredOn(e -> e.entrepriseId().equals(d.id())).hasSize(4);
        assertThat(bilan.enRetard()).isGreaterThanOrEqualTo(1);
    }

    private CabinetWorkspaceDtos.EcheanceDto creer(Dossier d, com.example.comptable.security.UtilisateurPrincipal auteur, LocalDate date) {
        return workspace.creerEcheance(auteur, new EcheanceRequest(d.id(), null, "ECHEANCE_INTERNE", "Échéance " + date, date, auteur.getId()));
    }
}
