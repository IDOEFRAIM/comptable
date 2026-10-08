package com.example.comptable.security;

import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phases B et C : la revue des constats, le lineage et le Hub documents sont le travail du cabinet. Un CLIENT (le
 * dirigeant) y recoit 403 sur CHAQUE route, y compris les actions qui modifient (ecarter un constat, associer, valider,
 * marquer un doublon) ; le cabinet n'est jamais bloque par la securite (un 404 metier sur un objet inconnu est normal).
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class PhaseCSecuriteHttpTest {

    private MockMvc mockMvc;
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtService jwtService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    @Autowired private EntrepriseService entrepriseService;

    private String jetonExpert;
    private String jetonClient;
    private UUID entrepriseId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Phase C");
        tenant.setSlug("cabinet-phase-c-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);
        Utilisateur expert = utilisateur(tenant, roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow());
        Utilisateur client = utilisateur(tenant, roleRepository.findByCode("CLIENT").orElseThrow());
        jetonExpert = jwtService.generateToken(new UtilisateurPrincipal(expert));
        jetonClient = jwtService.generateToken(new UtilisateurPrincipal(client));
        EntrepriseDto entreprise = entrepriseService.creer(new CreerEntrepriseRequest("PME Phase C", null, null, null, null, null, "CI", "XOF"),
                new UtilisateurPrincipal(expert));
        entrepriseId = entreprise.id();
        utilisateurEntrepriseRepository.save(new UtilisateurEntreprise(client.getId(), entrepriseId));
    }

    private Utilisateur utilisateur(Tenant tenant, Role role) {
        Utilisateur u = new Utilisateur();
        u.setTenant(tenant);
        u.setEmail("phase-c-" + UUID.randomUUID() + "@test.ci");
        u.setMotDePasse("hash");
        u.setNom("Test");
        u.setPrenom(role.getCode());
        u.setRoles(Set.of(role));
        return utilisateurRepository.save(u);
    }

    private List<MockHttpServletRequestBuilder> routes() {
        String base = "/api/entreprises/" + entrepriseId;
        UUID id = UUID.randomUUID();
        return List.of(
                get(base + "/revue/synthese"), get(base + "/revue/espace"), get(base + "/revue/constats"), get(base + "/revue/constats/" + id),
                // Phase E : le « Pourquoi ? » est interne au cabinet, comme la revue.
                get(base + "/revue/constats/" + id + "/investigation"), get(base + "/revue/investigations/TIERS/" + id),
                post(base + "/revue/executer"), post(base + "/revue/constats/" + id + "/ecarter"),
                get(base + "/lineage/documents/" + id), get(base + "/lineage/ecritures/" + id), get(base + "/lineage/sources/DOCUMENT/" + id),
                get(base + "/documents/hub"), get(base + "/documents/hub/synthese"), get(base + "/documents/hub/validation-lot/apercu"),
                post(base + "/documents/hub/validation-lot"),
                get(base + "/documents/" + id + "/candidats"), get(base + "/documents/" + id + "/originaux-possibles"),
                post(base + "/documents/" + id + "/associer"), post(base + "/documents/" + id + "/rejeter-proposition"),
                post(base + "/documents/" + id + "/marquer-doublon"), post(base + "/documents/" + id + "/valider"),
                post(base + "/documents/" + id + "/retraiter"), post(base + "/documents/" + id + "/lier-ecriture"),
                post(base + "/demandes-pieces/depuis-constat/" + id),
                get(base + "/cloture-readiness"),
                get(base + "/immobilisations/" + id + "/candidats-acquisition"));
    }

    private int statut(MockHttpServletRequestBuilder route, String jeton) throws Exception {
        MockHttpServletRequestBuilder requete = route.contentType(MediaType.APPLICATION_JSON).content("{}");
        if (jeton != null) {
            requete = requete.header("Authorization", "Bearer " + jeton);
        }
        return mockMvc.perform(requete).andReturn().getResponse().getStatus();
    }

    @Test
    void un_client_recoit_403_sur_toutes_les_routes_de_revue_de_lineage_et_du_hub() throws Exception {
        for (MockHttpServletRequestBuilder route : routes()) {
            assertThat(statut(route, jetonClient)).as(route.toString()).isEqualTo(403);
        }
    }

    @Test
    void le_cabinet_n_est_jamais_bloque_par_la_securite_sur_ces_routes() throws Exception {
        for (MockHttpServletRequestBuilder route : routes()) {
            assertThat(statut(route, jetonExpert)).as(route.toString()).isNotIn(401, 403);
        }
    }

    @Test
    void sans_jeton_aucune_route_n_est_accessible() throws Exception {
        for (MockHttpServletRequestBuilder route : routes()) {
            assertThat(statut(route, null)).as(route.toString()).isIn(401, 403);
        }
    }
}
