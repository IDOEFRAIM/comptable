package com.example.comptable.admin;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.agent.client.AiUsageService;
import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.common.tenant.TenantContext;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Supervision de l'IA (usage par cabinet, plafond mensuel) et de la santé de la plateforme. Chemin HTTP réel, vraie base ; le microservice IA n'est pas lancé en test (c'est le cas « en panne »). */
class AdminIaSanteHttpTest extends CollecteTestBase {

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;
    @Autowired AiUsageService usage;
    @Autowired AiServiceClient clientIa;

    private MockMvc mvc;
    private Utilisateur admin;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        admin = utilisateur(null, "SUPER_ADMIN", null);
    }

    @AfterEach
    void nettoyer() {
        TenantContext.clear();
        String ids = "select u.id from utilisateur u join utilisateur_role ur on ur.utilisateur_id = u.id join role r on r.id = ur.role_id where r.code = 'SUPER_ADMIN' and u.email like 'collecte-%@test.ci'";
        jdbc.update("delete from audit_log where utilisateur_id in (" + ids + ")");
        jdbc.update("delete from utilisateur_role where utilisateur_id in (" + ids + ")");
        jdbc.update("delete from utilisateur where id in (select id from (" + ids + ") t)");
    }

    private String jetonAdmin() {
        return "Bearer " + jwtService.generateToken(new UtilisateurPrincipal(utilisateurRepository.findById(admin.getId()).orElseThrow()));
    }

    private MockHttpServletResponse lire(String url, String jeton) throws Exception {
        var requete = get(url);
        if (jeton != null) {
            requete.header("Authorization", jeton);
        }
        return mvc.perform(requete).andReturn().getResponse();
    }

    private MockHttpServletResponse plafonner(UUID cabinet, String corps) throws Exception {
        return mvc.perform(post("/api/admin/cabinets/" + cabinet + "/plafond-ia").contentType(MediaType.APPLICATION_JSON).content(corps).header("Authorization", jetonAdmin())).andReturn().getResponse();
    }

    @Test
    void usageEtSante_sontReservesAuSuperAdmin() throws Exception {
        Dossier d = nouveauDossier(false);
        String expert = "Bearer " + jwtService.generateToken(d.expert());
        for (String url : new String[] {"/api/admin/ia/usage", "/api/admin/sante"}) {
            assertThat(lire(url, null).getStatus()).isEqualTo(401);
            assertThat(lire(url, expert).getStatus()).as("un membre de cabinet n'y accède pas").isEqualTo(403);
        }
        assertThat(plafonner(d.expert().getTenantId(), "{\"plafond\":5,\"motif\":\"Tentative sans droit\"}").getStatus()).isEqualTo(200); // avec le jeton admin
        assertThat(mvc.perform(post("/api/admin/cabinets/" + d.expert().getTenantId() + "/plafond-ia").contentType(MediaType.APPLICATION_JSON).content("{}").header("Authorization", expert)).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void chaqueAppelIaEstCompteParCabinet_echecCompris_etApparaitDansLUsage() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID cabinet = d.expert().getTenantId();
        long avant = jdbc.queryForObject("select count(*) from ai_call_log where tenant_id = ?", Long.class, cabinet);
        TenantContext.set(cabinet);
        var resultat = clientIa.extraire(new byte[] {1, 2, 3}, "facture.pdf", "application/pdf"); // ai-service absent : échec absorbé, comme en production quand il est en panne
        TenantContext.clear();

        assertThat(resultat).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from ai_call_log where tenant_id = ? and operation = 'extraction' and statut = 'ECHEC'", Long.class, cabinet)).isEqualTo(avant + 1);
        MockHttpServletResponse r = lire("/api/admin/ia/usage", jetonAdmin());
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentAsString()).contains("Cabinet Collecte").contains("\"operation\":\"extraction\"").contains("\"mois\"");
        assertThat(lire("/api/admin/ia/usage?mois=n-importe-quoi", jetonAdmin()).getStatus()).isEqualTo(422);
    }

    @Test
    void plafondMensuel_bloqueLesAppelsSuivants_sansCouperLeReste_etLesRefusNeComptentPasDansLUsage() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID cabinet = d.expert().getTenantId();
        assertThat(plafonner(cabinet, "{\"plafond\":1,\"motif\":\"abc\"}").getStatus()).as("motif trop court").isEqualTo(422);
        assertThat(plafonner(cabinet, "{\"plafond\":0,\"motif\":\"Plafond invalide\"}").getStatus()).isEqualTo(422);
        jdbc.update("delete from ai_call_log where tenant_id = ?", cabinet);
        MockHttpServletResponse ok = plafonner(cabinet, "{\"plafond\":1,\"motif\":\"Offre de départ du cabinet\"}");
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getContentAsString()).contains("\"plafond\":1");

        TenantContext.set(cabinet);
        assertThat(usage.plafondAtteint()).isFalse();
        clientIa.extraire(new byte[] {1}, "a.pdf", "application/pdf"); // 1er appel : fait (et échoue, microservice absent) ; il compte
        assertThat(usage.plafondAtteint()).as("1 appel sur 1 : plafond atteint").isTrue();
        var refuse = clientIa.extraire(new byte[] {1}, "b.pdf", "application/pdf"); // 2e appel : refusé avant tout appel réseau
        TenantContext.clear();

        assertThat(refuse.toString()).contains("plafond");
        assertThat(jdbc.queryForObject("select count(*) from ai_call_log where tenant_id = ? and statut = 'REFUSE_PLAFOND'", Long.class, cabinet)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_call_log where tenant_id = ? and statut <> 'REFUSE_PLAFOND'", Long.class, cabinet)).as("le refus n'augmente pas l'usage").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where tenant_id = ? and action = 'ADMIN_PLAFOND_IA_MODIFIE'", Long.class, cabinet)).isEqualTo(1);

        assertThat(plafonner(cabinet, "{\"plafond\":null,\"motif\":\"Retour sans plafond\"}").getStatus()).isEqualTo(200);
        TenantContext.set(cabinet);
        assertThat(usage.plafondAtteint()).as("sans plafond, plus de blocage").isFalse();
    }

    @Test
    void sante_dit_laVerite_surLaBase_lIa_etLaSauvegardeNonSurveillee() throws Exception {
        MockHttpServletResponse r = lire("/api/admin/sante", jetonAdmin());
        assertThat(r.getStatus()).isEqualTo(200);
        String corps = r.getContentAsString();
        assertThat(corps).contains("\"nom\":\"Base de données\"").contains("\"nom\":\"Microservice IA\"").contains("\"nom\":\"Serveur Warimara\"");
        assertThat(corps).as("la base répond").containsPattern("\"nom\":\"Base de données\",\"etat\":\"(OK|DEGRADE)\"");
        assertThat(corps).as("ai-service n'est pas lancé en test").contains("\"nom\":\"Microservice IA\",\"etat\":\"HORS_SERVICE\"");
        assertThat(corps).as("jamais « OK » sans dossier configuré").contains("\"etat\":\"NON_SURVEILLEE\"");
        assertThat(corps).containsPattern("\"versionBase\":\"\\d+\"");
    }
}
