package com.example.comptable.pilot;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Saisie hors ligne envoyée deux fois (le réseau retombe avant la réponse) : la pièce n'est créée qu'UNE fois et le renvoi reçoit la même réponse. Chemin HTTP réel (filtre d'idempotence), vraie base.
 */
class IdempotenceHttpTest extends CollecteTestBase {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
            0x1F, 0x15, (byte) 0xC4, (byte) 0x89, 0, 0, 0, 0x0A, 'I', 'D', 'A', 'T', 0x78, (byte) 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    private static final byte[] EXE = "MZ\u0090\u0000\u0003\u0000\u0000\u0000 This program cannot be run in DOS mode.".getBytes(StandardCharsets.ISO_8859_1);

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;

    private MockMvc mvc;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    private static String cle() {
        return "hl-" + UUID.randomUUID();
    }

    private long documents(Dossier d) {
        return jdbc.queryForObject("select count(*) from document where entreprise_id = ?", Long.class, d.id());
    }

    private MockHttpServletResponse deposer(Dossier d, String cle, byte[] contenu) throws Exception {
        var requete = multipart("/api/entreprises/" + d.id() + "/documents").file(new MockMultipartFile("fichier", "recu.png", "image/png", contenu))
                .param("typeDocument", "RECU").header("Authorization", "Bearer " + jwtService.generateToken(d.expert()));
        if (cle != null) {
            requete.header("Idempotency-Key", cle);
        }
        return mvc.perform(requete).andReturn().getResponse();
    }

    @Test
    void memeCle_deuxEnvois_uneSeulePiece_etMemeReponse() throws Exception {
        Dossier d = nouveauDossier(false);
        long avant = documents(d);
        String cle = cle();

        MockHttpServletResponse premier = deposer(d, cle, PNG);
        MockHttpServletResponse renvoi = deposer(d, cle, PNG);

        assertThat(premier.getStatus()).isBetween(200, 299);
        assertThat(renvoi.getStatus()).isEqualTo(premier.getStatus());
        assertThat(renvoi.getContentAsString()).isEqualTo(premier.getContentAsString());
        assertThat(renvoi.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(premier.getHeader("Idempotent-Replayed")).isNull();
        assertThat(documents(d)).as("une seule pièce créée malgré deux envois").isEqualTo(avant + 1);
    }

    @Test
    void clesDifferentes_deuxPieces() throws Exception {
        Dossier d = nouveauDossier(false);
        long avant = documents(d);
        deposer(d, cle(), PNG);
        deposer(d, cle(), PNG);
        assertThat(documents(d)).isEqualTo(avant + 2);
    }

    @Test
    void sansCle_comportementInchange() throws Exception {
        Dossier d = nouveauDossier(false);
        long avant = documents(d);
        MockHttpServletResponse r = deposer(d, null, PNG);
        deposer(d, null, PNG);
        assertThat(r.getHeader("Idempotent-Replayed")).isNull();
        assertThat(documents(d)).isEqualTo(avant + 2);
    }

    @Test
    void refusMetier_neMemorisePas_leRenvoiCorrigePeutReussir() throws Exception {
        Dossier d = nouveauDossier(false);
        long avant = documents(d);
        String cle = cle();

        assertThat(deposer(d, cle, EXE).getStatus()).isEqualTo(415);
        MockHttpServletResponse corrige = deposer(d, cle, PNG);

        assertThat(corrige.getStatus()).as("l'erreur n'est pas rejouée : la clé a été libérée").isBetween(200, 299);
        assertThat(corrige.getHeader("Idempotent-Replayed")).isNull();
        assertThat(documents(d)).isEqualTo(avant + 1);
    }

    @Test
    void memeCle_surUnAutreDossier_refusee422() throws Exception {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        String cle = cle();
        // même utilisateur, deux dossiers : on emprunte l'identité de l'expert du dossier A sur le dossier B
        MockHttpServletResponse premier = deposer(a, cle, PNG);
        assertThat(premier.getStatus()).isBetween(200, 299);
        var requete = multipart("/api/entreprises/" + b.id() + "/documents").file(new MockMultipartFile("fichier", "recu.png", "image/png", PNG))
                .param("typeDocument", "RECU").header("Authorization", "Bearer " + jwtService.generateToken(a.expert())).header("Idempotency-Key", cle);
        MockHttpServletResponse reponse = mvc.perform(requete).andReturn().getResponse();
        assertThat(reponse.getStatus()).isEqualTo(422);
        assertThat(reponse.getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(documents(b)).isZero();
    }

    @Test
    void cleInvalide_400() throws Exception {
        Dossier d = nouveauDossier(false);
        MockHttpServletResponse r = deposer(d, "x", PNG);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getContentAsString()).contains("IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    void laCleEstPropreAChaqueUtilisateur() throws Exception {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        String cle = cle();
        deposer(a, cle, PNG);
        MockHttpServletResponse r = deposer(b, cle, PNG);
        assertThat(r.getStatus()).isBetween(200, 299);
        assertThat(r.getHeader("Idempotent-Replayed")).as("la clé d'un autre utilisateur n'est pas rejouée").isNull();
        assertThat(documents(b)).isEqualTo(1);
    }

    @Test
    void requeteEncoreEnCours_503_puisAbandonneeApres2Minutes_reexecutee() throws Exception {
        Dossier d = nouveauDossier(false);
        String cle = cle();
        UUID utilisateur = d.expert().getId();
        String route = "POST /api/entreprises/" + d.id() + "/documents";
        jdbc.update("insert into requete_idempotente (utilisateur_id, cle, requete) values (?, ?, ?)", utilisateur, cle, route);

        MockHttpServletResponse enCours = deposer(d, cle, PNG);
        assertThat(enCours.getStatus()).isEqualTo(503);
        assertThat(enCours.getHeader("Retry-After")).isNotNull();
        assertThat(documents(d)).isZero();

        jdbc.update("update requete_idempotente set cree_le = now() - interval '3 minutes' where utilisateur_id = ? and cle = ?", utilisateur, cle);
        assertThat(deposer(d, cle, PNG).getStatus()).as("requête abandonnée : le renvoi s'exécute").isBetween(200, 299);
        assertThat(documents(d)).isEqualTo(1);
    }
}
