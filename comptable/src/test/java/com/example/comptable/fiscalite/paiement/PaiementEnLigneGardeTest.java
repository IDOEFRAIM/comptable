package com.example.comptable.fiscalite.paiement;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** En production, aucun paiement fictif ni confirmation manuelle ; hors production, les outils de test restent disponibles. */
class PaiementEnLigneGardeTest {

    private static PaiementEnLigneGarde garde(String profil, String esyntaxe, String fasoArzeka) {
        MockEnvironment env = new MockEnvironment();
        if (profil != null) {
            env.setActiveProfiles(profil);
        }
        return new PaiementEnLigneGarde(env, esyntaxe, fasoArzeka);
    }

    @Test
    void enProductionAvecSimulation_lePaiementEnLigneEtLaConfirmationManuelleSontRefuses() {
        PaiementEnLigneGarde g = garde("prod", "simulation", "simulation");
        assertThat(g.paiementEnLigneIndisponible()).isTrue();
        assertThatThrownBy(g::exigerInitiationPossible).isInstanceOf(ResponseStatusException.class).hasMessageContaining("paiement en ligne n'est pas disponible");
        assertThatThrownBy(g::exigerHorsProduction).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void enProductionAvecUnSeulFournisseurReel_ilReste_indisponible_tant_que_l_autre_est_simule() {
        assertThat(garde("prod", "reel", "simulation").paiementEnLigneIndisponible()).isTrue();
    }

    @Test
    void enProductionAvecLesDeuxFournisseursReels_lInitiationEstPermise_mais_pasLaConfirmationManuelle() {
        PaiementEnLigneGarde g = garde("prod", "reel", "reel");
        assertThat(g.paiementEnLigneIndisponible()).isFalse();
        assertThatCode(g::exigerInitiationPossible).doesNotThrowAnyException();
        assertThatThrownBy(g::exigerHorsProduction).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void horsProduction_lesOutilsDeTestRestentDisponibles() {
        for (String profil : new String[] {null, "staging", "test"}) {
            PaiementEnLigneGarde g = garde(profil, "simulation", "simulation");
            assertThat(g.paiementEnLigneIndisponible()).as(String.valueOf(profil)).isFalse();
            assertThatCode(g::exigerInitiationPossible).doesNotThrowAnyException();
            assertThatCode(g::exigerHorsProduction).doesNotThrowAnyException();
        }
    }
}
