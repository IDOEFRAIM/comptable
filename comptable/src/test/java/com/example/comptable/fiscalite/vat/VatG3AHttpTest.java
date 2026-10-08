package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.vat.VatDtos.PeriodView;
import com.example.comptable.fiscalite.vat.VatDtos.TreatmentRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** G3A vu de l'API HTTP réelle : paramètres de liste (pagination, recherche, tri, filtres), validation en masse, conflit de version 409, règlement interne, crédit d'ouverture, rôles et isolation des cabinets. */
class VatG3AHttpTest extends VatTestBase {

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
    void routesG3A_contratJson_conflit409_roles_et_multiTenant() throws Exception {
        Dossier d = dossier(true);
        UUID v1 = vente(d, "F1", "1000", "100");
        UUID v2 = vente(d, "F2", "500", "50");
        UUID m = mixte(d, "M1");
        PeriodView p = ouvrir(d);
        UtilisateurPrincipal collab = collaborateur(d);
        UtilisateurPrincipal client = client(d);
        Dossier autre = dossier(false);
        String base = "/api/entreprises/" + d.id() + "/fiscalite/tva/periodes/" + p.id();
        String json = MediaType.APPLICATION_JSON_VALUE;

        // liste : pagination, recherche, tri et filtres en paramètres
        MockHttpServletResponse liste = appeler(get(base + "/operations").param("cote", "SALES").param("q", "F1").param("tri", "amount").param("sens", "desc").param("taille", "5").param("page", "0")
                .param("statut", "OK").param("traitement", "OUTPUT_TAX").param("constatActif", "false").header("Authorization", jeton(collab)));
        assertThat(liste.getStatus()).isEqualTo(200);
        assertThat(liste.getContentAsString()).contains("\"items\"").contains("\"total\":1").contains("\"piece\":\"F1\"");
        assertThat(appeler(get(base + "/operations").param("statut", "TO_REVIEW").header("Authorization", jeton(collab))).getContentAsString()).contains("\"total\":1").contains("\"piece\":\"M1\"");
        assertThat(appeler(get(base + "/operations").header("Authorization", jeton(client))).getStatus()).isEqualTo(403);
        assertThat(appeler(get(base + "/operations").header("Authorization", jeton(autre.expert()))).getStatus()).isEqualTo(404);
        assertThat(appeler(get("/api/entreprises/" + d.id() + "/fiscalite/tva/periodes").param("statut", "OPEN").header("Authorization", jeton(collab))).getContentAsString()).contains(p.id().toString()).contains("\"version\"");
        assertThat(appeler(get("/api/entreprises/" + d.id() + "/fiscalite/tva/periodes").param("statut", "FINAL").header("Authorization", jeton(collab))).getContentAsString()).isEqualTo("[]");

        // la vue de période porte la version, la progression, la raison de blocage et le règlement
        String vue = appeler(get(base).header("Authorization", jeton(collab))).getContentAsString();
        assertThat(vue).contains("\"version\":").contains("\"progress\"").contains("\"readySteps\"").contains("\"regulatoryPackVerified\":true").contains("\"payment\"").contains("\"credit\"").contains("\"finalisationReasons\"");
        long version = Long.parseLong(vue.replaceAll(".*?\"version\":(\\d+).*", "$1"));

        // validation en masse : CLIENT 403, autre cabinet 404, sélection mélangée 422 ; sélection homogène acceptée
        String groupe = "{\"ecritureIds\":[\"" + v1 + "\",\"" + v2 + "\"],\"treatment\":\"OUTPUT_TAX\",\"reason\":\"Ventes confirmées\",\"expectedVersion\":" + version + "}";
        assertThat(appeler(post(base + "/operations/traitement-groupe").header("Authorization", jeton(client)).contentType(json).content(groupe)).getStatus()).isEqualTo(403);
        assertThat(appeler(post(base + "/operations/traitement-groupe").header("Authorization", jeton(autre.expert())).contentType(json).content(groupe)).getStatus()).isEqualTo(404);
        String melange = "{\"ecritureIds\":[\"" + v1 + "\",\"" + m + "\"],\"treatment\":\"OUTPUT_TAX\",\"reason\":\"x\"}";
        assertThat(appeler(post(base + "/operations/traitement-groupe").header("Authorization", jeton(collab)).contentType(json).content(melange)).getStatus()).isEqualTo(422);
        MockHttpServletResponse ok = appeler(post(base + "/operations/traitement-groupe").header("Authorization", jeton(collab)).contentType(json).content(groupe));
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getContentAsString()).contains("\"applied\":2");

        // conflit de version : la version lue avant la validation en masse est périmée → 409 (décision, ajustement, transition, crédit, paiement)
        assertThat(appeler(put(base + "/operations/" + m + "/traitement").header("Authorization", jeton(collab)).contentType(json)
                .content("{\"treatment\":\"OUT_OF_SCOPE\",\"reason\":\"Hors champ\",\"expectedVersion\":" + version + "}")).getStatus()).isEqualTo(409);
        assertThat(appeler(post(base + "/ajustements").header("Authorization", jeton(collab)).contentType(json)
                .content("{\"kind\":\"MANUAL\",\"effect\":\"OUTPUT\",\"amount\":1,\"reason\":\"x\",\"expectedVersion\":" + version + "}")).getStatus()).isEqualTo(409);
        assertThat(appeler(post(base + "/preparer").header("Authorization", jeton(collab)).contentType(json).content("{\"expectedVersion\":" + version + "}")).getStatus()).isEqualTo(409);
        assertThat(appeler(put(base + "/credit-ouverture").header("Authorization", jeton(collab)).contentType(json)
                .content("{\"amount\":10,\"reason\":\"x\",\"source\":\"y\",\"expectedVersion\":" + version + "}")).getStatus()).isEqualTo(409);
        assertThat(appeler(post(base + "/paiements").header("Authorization", jeton(collab)).contentType(json)
                .content("{\"amount\":1,\"paymentDate\":\"" + LocalDate.now() + "\",\"source\":\"MANUAL\",\"idempotencyKey\":\"k\",\"expectedVersion\":" + version + "}")).getStatus()).isEqualTo(409);

        // règlement : lecture ; pas de paiement avant la finalisation (409) ; CLIENT 403 ; autre cabinet 404
        long courante = Long.parseLong(appeler(get(base).header("Authorization", jeton(collab))).getContentAsString().replaceAll(".*?\"version\":(\\d+).*", "$1"));
        assertThat(appeler(get(base + "/paiements").header("Authorization", jeton(collab))).getContentAsString()).contains("\"paidAmount\"").contains("\"payments\"");
        assertThat(appeler(post(base + "/paiements").header("Authorization", jeton(collab)).contentType(json)
                .content("{\"amount\":1,\"paymentDate\":\"" + LocalDate.now() + "\",\"source\":\"MANUAL\",\"idempotencyKey\":\"k\",\"expectedVersion\":" + courante + "}")).getStatus()).isEqualTo(409);
        assertThat(appeler(post(base + "/paiements").header("Authorization", jeton(client)).contentType(json).content("{\"amount\":1,\"expectedVersion\":" + courante + "}")).getStatus()).isEqualTo(403);
        assertThat(appeler(get(base + "/paiements").header("Authorization", jeton(autre.expert()))).getStatus()).isEqualTo(404);
        assertThat(appeler(post(base + "/paiements/" + UUID.randomUUID() + "/annuler").header("Authorization", jeton(autre.expert())).contentType(json).content("{\"reason\":\"x\",\"expectedVersion\":" + courante + "}")).getStatus()).isEqualTo(404);

        // crédit d'ouverture : saisie avec la version courante, lecture, état « à revoir » sans règle de report
        MockHttpServletResponse credit = appeler(put(base + "/credit-ouverture").header("Authorization", jeton(collab)).contentType(json).content("{\"amount\":10,\"reason\":\"Reprise justifiée\",\"source\":\"Déclaration précédente\",\"expectedVersion\":" + courante + "}"));
        assertThat(credit.getStatus()).isEqualTo(200);
        assertThat(credit.getContentAsString()).contains("\"openingState\":\"TO_REVIEW\"").contains("\"openingAmount\":10");
        assertThat(appeler(get(base + "/credit-ouverture").header("Authorization", jeton(autre.expert()))).getStatus()).isEqualTo(404);
        assertThat(appeler(put(base + "/credit-ouverture").header("Authorization", jeton(client)).contentType(json).content("{\"amount\":10,\"expectedVersion\":" + courante + "}")).getStatus()).isEqualTo(403);
    }

    @Test
    void expectedVersionObligatoire_422_perimee409_correcte_plusUn_et_doubleFinalisation409() throws Exception {
        Dossier d = dossier(true);
        UUID v1 = vente(d, "F1", "1000", "100");
        achat(d, "A1", "400", "40");
        PeriodView p = ouvrir(d);
        UtilisateurPrincipal collab = collaborateur(d);
        String base = "/api/entreprises/" + d.id() + "/fiscalite/tva/periodes/" + p.id();
        String json = MediaType.APPLICATION_JSON_VALUE;
        String h = jeton(collab);

        // absence de expectedVersion : 422 sur chaque mutation utilisateur, rien n'est modifié
        String[][] sansVersion = {
                {"PUT", "/operations/" + v1 + "/traitement", "{\"treatment\":\"EXEMPT\",\"reason\":\"x\"}"},
                {"POST", "/operations/traitement-groupe", "{\"ecritureIds\":[\"" + v1 + "\"],\"treatment\":\"OUTPUT_TAX\",\"reason\":\"x\"}"},
                {"POST", "/ajustements", "{\"kind\":\"MANUAL\",\"effect\":\"OUTPUT\",\"amount\":1,\"reason\":\"x\"}"},
                {"POST", "/ajustements/" + UUID.randomUUID() + "/annuler", "{\"reason\":\"x\"}"},
                {"PUT", "/credit-ouverture", "{\"amount\":10,\"reason\":\"x\",\"source\":\"y\"}"},
                {"POST", "/paiements", "{\"amount\":1,\"paymentDate\":\"" + LocalDate.now() + "\",\"source\":\"MANUAL\",\"idempotencyKey\":\"k\"}"},
                {"POST", "/paiements/" + UUID.randomUUID() + "/annuler", "{\"reason\":\"x\"}"},
                {"POST", "/preparer", "{}"}, {"POST", "/revoir", "{}"}, {"POST", "/rouvrir", "{}"}, {"POST", "/finaliser", "{}"}};
        for (String[] r : sansVersion) {
            var requete = "PUT".equals(r[0]) ? put(base + r[1]) : post(base + r[1]);
            assertThat(appeler(requete.header("Authorization", h).contentType(json).content(r[2])).getStatus()).as(r[0] + " " + r[1]).isEqualTo(422);
        }
        assertThat(tva.detail(d.expert(), d.id(), p.id()).version()).isEqualTo(p.version());

        // version correcte : la mutation passe et la version vaut N+1 ; la même version rejouée est périmée : 409
        long v = p.version();
        MockHttpServletResponse ok = appeler(put(base + "/operations/" + v1 + "/traitement").header("Authorization", h).contentType(json)
                .content("{\"treatment\":\"EXEMPT\",\"reason\":\"Exonération justifiée\",\"expectedVersion\":" + v + "}"));
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getContentAsString()).contains("\"version\":" + (v + 1));
        assertThat(appeler(put(base + "/operations/" + v1 + "/traitement").header("Authorization", h).contentType(json)
                .content("{\"treatment\":\"OUTPUT_TAX\",\"reason\":\"Rejeu\",\"expectedVersion\":" + v + "}")).getStatus()).isEqualTo(409);

        // double finalisation HTTP : le second appel est refusé (409) et le snapshot n'est jamais recréé
        tva.decider(collab, d.id(), p.id(), v1, new TreatmentRequest("OUTPUT_TAX", null, "Retour à la taxation"));
        long courante = tva.detail(d.expert(), d.id(), p.id()).version();
        assertThat(appeler(post(base + "/preparer").header("Authorization", h).contentType(json).content("{\"expectedVersion\":" + courante + "}")).getStatus()).isEqualTo(200);
        assertThat(appeler(post(base + "/revoir").header("Authorization", jeton(d.expert())).contentType(json).content("{\"expectedVersion\":" + (courante + 1) + "}")).getStatus()).isEqualTo(200);
        String expert = jeton(d.expert());
        MockHttpServletResponse fin = appeler(post(base + "/finaliser").header("Authorization", expert).contentType(json).content("{\"expectedVersion\":" + (courante + 2) + "}"));
        assertThat(fin.getStatus()).isEqualTo(200);
        assertThat(appeler(post(base + "/finaliser").header("Authorization", expert).contentType(json).content("{\"expectedVersion\":" + (courante + 3) + "}")).getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from tax_declaration_snapshot where tax_period_id = ?", Long.class, p.id())).isEqualTo(1L);
    }
}
