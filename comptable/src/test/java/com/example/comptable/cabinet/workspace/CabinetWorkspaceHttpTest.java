package com.example.comptable.cabinet.workspace;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Le Cabinet Workspace vu de l'API HTTP réelle : contrat JSON, rôles (CLIENT refusé partout, collaborateur refusé à l'affectation) et isolation entre cabinets. */
class CabinetWorkspaceHttpTest extends CollecteTestBase {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtService jwtService;
    @Autowired ExerciceComptableRepository exerciceRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    private String jeton(UtilisateurPrincipal p) {
        return "Bearer " + jwtService.generateToken(p);
    }

    private MockHttpServletResponse appeler(org.springframework.test.web.servlet.RequestBuilder r) throws Exception {
        return mockMvc.perform(r).andReturn().getResponse();
    }

    @Test
    void contratJson_rolesEtMultiTenant() throws Exception {
        Dossier d = nouveauDossier(true);
        Tenant tenant = tenantRepository.findById(d.expert().getTenantId()).orElseThrow();
        UtilisateurPrincipal collab = new UtilisateurPrincipal(utilisateur(tenant, "COLLABORATEUR", null));
        UtilisateurPrincipal client = new UtilisateurPrincipal(utilisateur(tenant, "CLIENT", null));
        Dossier autreCabinet = nouveauDossier(false);
        UUID exercice = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();

        // contrat : le backend fournit tout (statut, libellé, priorité, étapes) ; les listes sont paginées
        MockHttpServletResponse dashboard = appeler(get("/api/cabinet/dashboard").header("Authorization", jeton(d.expert())));
        assertThat(dashboard.getStatus()).isEqualTo(200);
        assertThat(dashboard.getContentAsString()).contains("\"summary\"").contains("\"workQueue\"").contains("\"reviewQueue\"").contains("\"deadlines\"").contains("\"dossiersActifs\"");

        MockHttpServletResponse dossiers = appeler(get("/api/cabinet/dossiers").param("filtre", "tous").param("q", d.entreprise().raisonSociale()).param("tri", "priorite").param("taille", "5")
                .header("Authorization", jeton(collab)));
        assertThat(dossiers.getStatus()).isEqualTo(200);
        assertThat(dossiers.getContentAsString()).contains("\"statutLibelle\"").contains("\"etapesTotal\":7").contains("\"priorite\"").contains(d.entreprise().raisonSociale());
        assertThat(appeler(get("/api/cabinet/dossiers").param("filtre", "inconnu").header("Authorization", jeton(collab))).getStatus()).isEqualTo(400);
        assertThat(appeler(get("/api/cabinet/work-queue").header("Authorization", jeton(collab))).getStatus()).isEqualTo(200);
        assertThat(appeler(get("/api/cabinet/deadlines").param("periode", "EN_RETARD").header("Authorization", jeton(collab))).getContentAsString()).contains("\"enRetard\"");
        assertThat(appeler(get("/api/cabinet/membres").header("Authorization", jeton(collab))).getContentAsString()).contains("nom");

        // CLIENT : aucun accès à aucune route du Cabinet Workspace
        for (String route : new String[] {"/api/cabinet/dashboard", "/api/cabinet/dossiers", "/api/cabinet/work-queue", "/api/cabinet/deadlines", "/api/cabinet/membres", "/api/cabinet/equipe"}) {
            assertThat(appeler(get(route).header("Authorization", jeton(client))).getStatus()).as(route).isEqualTo(403);
        }
        assertThat(appeler(get("/api/cabinet/dashboard")).getStatus()).isIn(401, 403);

        // affectation : l'expert peut, le collaborateur non ; autre cabinet -> 404
        String corps = "{\"collaborateurId\":\"" + collab.getId() + "\",\"expertId\":\"" + d.expert().getId() + "\"}";
        String route = "/api/cabinet/dossiers/" + d.id() + "/" + exercice + "/affectation";
        assertThat(appeler(patch(route).header("Authorization", jeton(collab)).contentType(MediaType.APPLICATION_JSON).content(corps)).getStatus()).isEqualTo(403);
        MockHttpServletResponse ok = appeler(patch(route).header("Authorization", jeton(d.expert())).contentType(MediaType.APPLICATION_JSON).content(corps));
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getContentAsString()).contains(collab.getId().toString());
        assertThat(appeler(patch(route).header("Authorization", jeton(autreCabinet.expert())).contentType(MediaType.APPLICATION_JSON).content("{}")).getStatus()).isEqualTo(404);

        MockHttpServletResponse masse = appeler(patch("/api/cabinet/dossiers/affectation").header("Authorization", jeton(d.expert())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dossiers\":[{\"entrepriseId\":\"" + d.id() + "\",\"exerciceId\":\"" + exercice + "\"}],\"collaborateurId\":\"" + collab.getId() + "\"}"));
        assertThat(masse.getStatus()).isEqualTo(200);
        assertThat(masse.getContentAsString()).contains("\"dossiers\":1");

        // échéances : création (201) puis marquage comme faite
        MockHttpServletResponse creee = appeler(post("/api/cabinet/echeances").header("Authorization", jeton(collab)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"entrepriseId\":\"" + d.id() + "\",\"type\":\"RELANCE_CLIENT\",\"libelle\":\"Relancer le client\",\"date\":\"" + LocalDate.now().plusDays(2) + "\"}"));
        assertThat(creee.getStatus()).isEqualTo(201);
        String id = creee.getContentAsString().replaceAll(".*\"id\":\"([0-9a-f-]{36})\".*", "$1");
        assertThat(appeler(patch("/api/cabinet/echeances/" + id).header("Authorization", jeton(autreCabinet.expert())).contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"FAITE\"}")).getStatus())
                .isEqualTo(404);
        assertThat(appeler(patch("/api/cabinet/echeances/" + id).header("Authorization", jeton(collab)).contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"FAITE\"}")).getStatus()).isEqualTo(200);

        // équipe : lecture ouverte au cabinet, ajout / modification réservés à l'expert (contrat JSON), isolation entre cabinets
        assertThat(appeler(get("/api/cabinet/equipe").header("Authorization", jeton(collab))).getContentAsString()).contains("\"dossiersAffectes\"").contains("\"statut\":\"ACTIF\"");
        String nouveau = "{\"email\":\"http-" + UUID.randomUUID() + "@test.ci\",\"nom\":\"Kone\",\"prenom\":\"Awa\",\"role\":\"COLLABORATEUR\"}";
        assertThat(appeler(post("/api/cabinet/membres").header("Authorization", jeton(collab)).contentType(MediaType.APPLICATION_JSON).content(nouveau)).getStatus()).isEqualTo(403);
        MockHttpServletResponse ajoute = appeler(post("/api/cabinet/membres").header("Authorization", jeton(d.expert())).contentType(MediaType.APPLICATION_JSON).content(nouveau));
        assertThat(ajoute.getStatus()).isEqualTo(201);
        assertThat(ajoute.getContentAsString()).contains("motDePasseTemporaire").contains("\"role\":\"COLLABORATEUR\"");
        assertThat(appeler(post("/api/cabinet/membres").header("Authorization", jeton(d.expert())).contentType(MediaType.APPLICATION_JSON).content(nouveau)).getStatus()).isEqualTo(409);
        String idMembre = ajoute.getContentAsString().replaceAll(".*\"membre\":[{]\"id\":\"([0-9a-f-]{36})\".*", "$1");
        assertThat(appeler(patch("/api/cabinet/membres/" + idMembre).header("Authorization", jeton(autreCabinet.expert())).contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"INACTIF\"}")).getStatus()).isEqualTo(404);
        assertThat(appeler(patch("/api/cabinet/membres/" + idMembre).header("Authorization", jeton(collab)).contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"INACTIF\"}")).getStatus()).isEqualTo(403);
        assertThat(appeler(patch("/api/cabinet/membres/" + idMembre).header("Authorization", jeton(d.expert())).contentType(MediaType.APPLICATION_JSON).content("{\"statut\":\"INACTIF\"}")).getContentAsString()).contains("INACTIF");
        assertThat(appeler(post("/api/cabinet/controles/synchroniser").header("Authorization", jeton(collab))).getStatus()).isEqualTo(403);
        assertThat(appeler(post("/api/cabinet/controles/synchroniser").header("Authorization", jeton(d.expert()))).getContentAsString()).contains("\"dossiers\"");

        // le portefeuille de l'autre cabinet ne contient pas ce dossier
        assertThat(appeler(get("/api/cabinet/dossiers").param("q", d.entreprise().raisonSociale()).header("Authorization", jeton(autreCabinet.expert()))).getContentAsString())
                .doesNotContain(d.entreprise().raisonSociale());
    }
}
