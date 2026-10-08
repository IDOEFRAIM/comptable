package com.example.comptable.fiscalite.vat;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** L'espace TVA vu de l'API HTTP réelle : contrat JSON, rôles (CLIENT refusé, collaborateur refusé à la revue / finalisation / vérification d'un cadre) et isolation entre cabinets (404). */
class VatHttpTest extends CollecteTestBase {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    private String jeton(UtilisateurPrincipal p) {
        return "Bearer " + jwtService.generateToken(p);
    }

    private MockHttpServletResponse appeler(RequestBuilder r) throws Exception {
        return mockMvc.perform(r).andReturn().getResponse();
    }

    @Test
    void contratJson_roles_et_multiTenant() throws Exception {
        Dossier d = nouveauDossier(false);
        Tenant tenant = tenantRepository.findById(d.expert().getTenantId()).orElseThrow();
        UtilisateurPrincipal collab = new UtilisateurPrincipal(utilisateur(tenant, "COLLABORATEUR", null));
        UtilisateurPrincipal client = new UtilisateurPrincipal(utilisateur(tenant, "CLIENT", null));
        Dossier autre = nouveauDossier(false);
        String base = "/api/entreprises/" + d.id() + "/fiscalite/tva";

        // cadre fiscal : collaborateur refusé, expert crée (DRAFT) puis ne peut vérifier sans source
        String cadre = "{\"jurisdiction\":\"CI\",\"taxType\":\"TVA\",\"version\":\"HTTP-1\",\"effectiveFrom\":\"2000-01-01\",\"rules\":{\"rates\":[{\"code\":\"T10\",\"label\":\"Taux fictif\",\"percent\":10}],"
                + "\"periodicity\":\"MONTHLY\",\"toleranceAmount\":1,\"rateMatchTolerancePoints\":0.5},\"sourceReferences\":[]}";
        assertThat(appeler(post("/api/cabinet/fiscal/cadres").header("Authorization", jeton(collab)).contentType(MediaType.APPLICATION_JSON).content(cadre)).getStatus()).isEqualTo(403);
        MockHttpServletResponse cree = appeler(post("/api/cabinet/fiscal/cadres").header("Authorization", jeton(d.expert())).contentType(MediaType.APPLICATION_JSON).content(cadre));
        assertThat(cree.getStatus()).isEqualTo(201);
        assertThat(cree.getContentAsString()).contains("\"status\":\"DRAFT\"").contains("\"definitionHash\"");
        String id = cree.getContentAsString().replaceAll(".*\"id\":\"([0-9a-f-]{36})\".*", "$1");
        assertThat(appeler(post("/api/cabinet/fiscal/cadres/" + id + "/verifier").header("Authorization", jeton(d.expert()))).getStatus()).isEqualTo(422);
        assertThat(appeler(post("/api/cabinet/fiscal/cadres/" + id + "/verifier").header("Authorization", jeton(autre.expert()))).getStatus()).isEqualTo(404);
        assertThat(appeler(get("/api/cabinet/fiscal/cadres").header("Authorization", jeton(client))).getStatus()).isEqualTo(403);

        // période : CLIENT refusé partout, autre cabinet 404, collaborateur peut ouvrir et lire
        LocalDate debut = LocalDate.now().withDayOfMonth(1);
        String periode = "{\"periodStart\":\"" + debut + "\",\"periodEnd\":\"" + debut.plusMonths(1).minusDays(1) + "\"}";
        assertThat(appeler(post(base + "/periodes").header("Authorization", jeton(client)).contentType(MediaType.APPLICATION_JSON).content(periode)).getStatus()).isEqualTo(403);
        assertThat(appeler(get(base + "/periodes").header("Authorization", jeton(client))).getStatus()).isEqualTo(403);
        assertThat(appeler(get(base + "/periodes").header("Authorization", jeton(autre.expert()))).getStatus()).isEqualTo(404);
        MockHttpServletResponse ouverte = appeler(post(base + "/periodes").header("Authorization", jeton(collab)).contentType(MediaType.APPLICATION_JSON).content(periode));
        assertThat(ouverte.getStatus()).isEqualTo(201);
        String json = ouverte.getContentAsString();
        assertThat(json).contains("\"status\":\"OPEN\"").contains("\"totals\"").contains("\"permissions\"").contains("\"basis\":\"ACCOUNTED_UNVERIFIED\"").contains("\"finalisationBlockers\"");
        String periodeId = json.replaceAll(".*?\"id\":\"([0-9a-f-]{36})\".*", "$1");

        for (String route : new String[] {"", "/operations", "/ajustements", "/controles", "/declaration"}) {
            assertThat(appeler(get(base + "/periodes/" + periodeId + route).header("Authorization", jeton(collab))).getStatus()).as(route).isEqualTo(200);
            assertThat(appeler(get(base + "/periodes/" + periodeId + route).header("Authorization", jeton(autre.expert()))).getStatus()).as("autre cabinet " + route).isEqualTo(404);
        }
        MockHttpServletResponse operations = appeler(get(base + "/periodes/" + periodeId + "/operations").param("cote", "OUTPUT").param("taille", "5").header("Authorization", jeton(collab)));
        assertThat(operations.getContentAsString()).contains("\"items\"").contains("\"total\":0");

        // revue / finalisation : collaborateur 403 ; l'expert est refusé tant que la période n'est pas prête (409)
        assertThat(appeler(post(base + "/periodes/" + periodeId + "/revoir").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}").header("Authorization", jeton(collab))).getStatus()).isEqualTo(403);
        assertThat(appeler(post(base + "/periodes/" + periodeId + "/finaliser").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}").header("Authorization", jeton(collab))).getStatus()).isEqualTo(403);
        assertThat(appeler(post(base + "/periodes/" + periodeId + "/revoir").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}").header("Authorization", jeton(d.expert()))).getStatus()).isEqualTo(409);
        assertThat(appeler(post(base + "/periodes/" + periodeId + "/finaliser").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}").header("Authorization", jeton(d.expert()))).getStatus()).isEqualTo(409);
        assertThat(appeler(get(base + "/periodes/" + periodeId + "/snapshot").header("Authorization", jeton(d.expert()))).getStatus()).isEqualTo(404);
        assertThat(appeler(post(base + "/periodes/" + UUID.randomUUID() + "/preparer").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}").header("Authorization", jeton(collab))).getStatus()).isEqualTo(404);
    }
}
