package com.example.comptable.collecte;

import com.example.comptable.collecte.domain.TypeMessage;
import com.example.comptable.collecte.service.CollecteConfig;
import com.example.comptable.collecte.service.DepotLienService;
import com.example.comptable.collecte.service.MessageTemplates;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regles pures de la collecte : cadence de relance, gabarits sans donnee interne, jeton de depot signe. */
class CollecteUnitTest {

    private static CollecteConfig config(String delais, String backoff, int tentatives) {
        return new CollecteConfig(delais, 2, tentatives, backoff, 60, 45, "https://depot.test/d", "PREMIER", "tpl", "tpl", 25, false);
    }

    @Test
    void cadenceDeRelance_j3PuisJ7PuisPlusRien() {
        CollecteConfig c = config("3,7", "5,30,120", 4);

        assertThat(c.delaiAvantRelance(0)).hasValue(3);
        assertThat(c.delaiAvantRelance(1)).hasValue(7);
        assertThat(c.delaiAvantRelance(2)).isEmpty();
        assertThat(c.maxRelances()).isEqualTo(2);
    }

    @Test
    void retryTechnique_backoffCroissantEtPlafonne() {
        CollecteConfig c = config("3,7", "5,30,120", 4);

        assertThat(c.backoffMinutes(1)).isEqualTo(5);
        assertThat(c.backoffMinutes(2)).isEqualTo(30);
        assertThat(c.backoffMinutes(3)).isEqualTo(120);
        assertThat(c.backoffMinutes(9)).isEqualTo(120);
        assertThat(c.maxTentatives()).isEqualTo(4);
    }

    @Test
    void gabarit_neRendJamaisUnIdentifiantInterne() {
        MessageTemplates templates = new MessageTemplates(config("3,7", "5", 3));
        UUID demandeId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("entreprise", "PME Test", "lienDepot", "https://depot.test/d/abc.def",
                "elements", List.of(Map.of("demandeId", demandeId.toString(), "libelle", "Achat carburant", "montant", new BigDecimal("12500"),
                        "devise", "XOF", "date", "2026-09-12", "tiers", "Station XYZ")));

        for (TypeMessage type : TypeMessage.values()) {
            String texte = templates.rendre(type, payload, "Awa").texte();
            assertThat(texte).contains("PME Test").contains("Achat carburant").contains("https://depot.test/d/abc.def")
                    .doesNotContain(demandeId.toString()).doesNotContain("demandeId");
        }
    }

    @Test
    void jetonDeDepot_signe_refuseLeJetonForgeEtLaDemandeClose() {
        DemandePieceRepository repository = mock(DemandePieceRepository.class);
        DepotLienService service = new DepotLienService(repository, config("3,7", "5", 3), "secret-de-test");
        DemandePiece ouverte = new DemandePiece();
        ouverte.setId(UUID.randomUUID());
        ouverte.setStatut(StatutDemandePiece.EN_ATTENTE);
        ouverte.setTokenExpireAt(OffsetDateTime.now().plusDays(3));
        when(repository.findById(ouverte.getId())).thenReturn(Optional.of(ouverte));

        String jeton = service.jeton(ouverte.getId());
        assertThat(service.verifier(jeton)).containsSame(ouverte);

        // Signature d'une autre demande, signature tronquee, secret different : tout est refuse.
        assertThat(service.verifier(jeton.split("\\.")[0] + "." + service.jeton(UUID.randomUUID()).split("\\.")[1])).isEmpty();
        assertThat(service.verifier(jeton.substring(0, jeton.length() - 3))).isEmpty();
        assertThat(new DepotLienService(repository, config("3,7", "5", 3), "autre-secret").verifier(jeton)).isEmpty();
        assertThat(service.verifier(null)).isEmpty();
        assertThat(service.verifier("x".repeat(500))).isEmpty();

        ouverte.setStatut(StatutDemandePiece.ANNULEE);
        assertThat(service.verifier(jeton)).isEmpty();
        ouverte.setStatut(StatutDemandePiece.EN_ATTENTE);
        ouverte.setTokenExpireAt(OffsetDateTime.now().minusMinutes(1));
        assertThat(service.verifier(jeton)).isEmpty();
    }
}
