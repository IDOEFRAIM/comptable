package com.example.comptable.security;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.copilot.dto.CopilotExplainResponseDTO;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mission P1.5 (section 61) : "Tester au niveau HTTP reel, pas uniquement les services." Aucun
 * test de ce type n'existait dans le projet avant P1.5 (confirme par l'audit initial - tous les
 * tests P0/P1 appellent les services Java directement avec un {@code UtilisateurPrincipal}
 * construit a la main, jamais via une requete HTTP authentifiee traversant
 * {@code JwtAuthFilter}/{@code SecurityConfig}). MockMvc dispatche de vraies requetes HTTP avec de
 * vrais jetons JWT (mintes par le meme {@link JwtService} que la production) a travers le vrai
 * {@code DispatcherServlet} et la vraie chaine de filtres de securite. MockMvc est construit
 * manuellement depuis le {@link WebApplicationContext} (meme pattern que
 * {@code ComptabilisationIntegrationTest} : {@code spring.docker.compose.skip.in-tests=false} en
 * propriete explicite, sans quoi Docker Compose n'est jamais sollicite pour la datasource de
 * test).
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class P1_5SecuriteHttpE2ETest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UtilisateurRepository utilisateurRepository;
    @Autowired
    private UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    @Autowired
    private EntrepriseService entrepriseService;

    @MockitoBean
    private AiServiceClient aiServiceClient;

    private String jetonExpertTenantA;
    private String jetonClientTenantA;
    private UUID entrepriseTenantAId;
    private UUID entrepriseTenantBId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();

        when(aiServiceClient.copilotExplain(any())).thenReturn(
                Optional.of(new CopilotExplainResponseDTO("Reponse de test.")));

        Role roleExpert = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Role roleClient = roleRepository.findByCode("CLIENT").orElseThrow();

        Tenant tenantA = nouveauTenant("Cabinet A");
        Tenant tenantB = nouveauTenant("Cabinet B");

        Utilisateur expertA = nouvelUtilisateur(tenantA, roleExpert);
        UtilisateurPrincipalEtJeton expert = principalEtJeton(expertA);
        jetonExpertTenantA = expert.jeton;

        Utilisateur clientA = nouvelUtilisateur(tenantA, roleClient);
        jetonClientTenantA = principalEtJeton(clientA).jeton;

        EntrepriseDto entrepriseA = entrepriseService.creer(
                new CreerEntrepriseRequest("SARL Tenant A", null, null, null, null, null, "BF", "XOF"),
                expert.principal);
        entrepriseTenantAId = entrepriseA.id();
        utilisateurEntrepriseRepository.save(new UtilisateurEntreprise(clientA.getId(), entrepriseTenantAId));

        Utilisateur expertB = nouvelUtilisateur(tenantB, roleExpert);
        EntrepriseDto entrepriseB = entrepriseService.creer(
                new CreerEntrepriseRequest("SARL Tenant B", null, null, null, null, null, "BF", "XOF"),
                principalEtJeton(expertB).principal);
        entrepriseTenantBId = entrepriseB.id();
    }

    private Tenant nouveauTenant(String nom) {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale(nom);
        tenant.setSlug(nom.toLowerCase().replace(" ", "-") + "-" + UUID.randomUUID());
        tenant.setPays("BF");
        return tenantRepository.save(tenant);
    }

    private Utilisateur nouvelUtilisateur(Tenant tenant, Role role) {
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("p1-5-http-" + UUID.randomUUID() + "@test.bf");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom(role.getCode());
        utilisateur.setRoles(Set.of(role));
        return utilisateurRepository.save(utilisateur);
    }

    private record UtilisateurPrincipalEtJeton(UtilisateurPrincipal principal, String jeton) {
    }

    private UtilisateurPrincipalEtJeton principalEtJeton(Utilisateur utilisateur) {
        UtilisateurPrincipal principal = new UtilisateurPrincipal(utilisateur);
        return new UtilisateurPrincipalEtJeton(principal, jwtService.generateToken(principal));
    }

    @Test
    void requete_sans_jeton_est_rejetee() throws Exception {
        // Pas de AuthenticationEntryPoint dedie dans SecurityConfig : une requete anonyme rejetee
        // par ".anyRequest().authenticated()" recoit 403 (comportement reel confirme par ce test),
        // pas 401 - verifie ici que c'est bien le cas plutot que de le supposer.
        mockMvc.perform(get("/api/entreprises/" + entrepriseTenantAId + "/tableau-de-bord"))
                .andExpect(status().isForbidden());
    }

    @Test
    void expert_comptable_ne_voit_pas_lentreprise_dun_autre_tenant() throws Exception {
        mockMvc.perform(get("/api/entreprises/" + entrepriseTenantBId + "/tableau-de-bord")
                        .header("Authorization", "Bearer " + jetonExpertTenantA))
                .andExpect(status().isNotFound());
    }

    @Test
    void client_ne_peut_pas_appeler_le_cockpit_cabinet_global() throws Exception {
        mockMvc.perform(get("/api/cabinet/cockpit")
                        .header("Authorization", "Bearer " + jetonClientTenantA))
                .andExpect(status().isForbidden());
    }

    @Test
    void client_ne_peut_pas_appeler_le_tableau_de_bord_reserve_au_cabinet() throws Exception {
        mockMvc.perform(get("/api/entreprises/" + entrepriseTenantAId + "/tableau-de-bord")
                        .header("Authorization", "Bearer " + jetonClientTenantA))
                .andExpect(status().isForbidden());
    }

    @Test
    void client_peut_lister_les_documents_de_sa_propre_entreprise() throws Exception {
        mockMvc.perform(get("/api/entreprises/" + entrepriseTenantAId + "/documents")
                        .header("Authorization", "Bearer " + jetonClientTenantA))
                .andExpect(status().isOk());
    }

    @Test
    void client_ne_peut_pas_lister_les_documents_dune_entreprise_a_laquelle_il_nest_pas_rattache() throws Exception {
        mockMvc.perform(get("/api/entreprises/" + entrepriseTenantBId + "/documents")
                        .header("Authorization", "Bearer " + jetonClientTenantA))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void client_ne_peut_pas_appeler_le_copilot_comptable_mais_peut_appeler_le_copilot_dirigeant() throws Exception {
        String corps = "{\"question\":\"Mon dossier est-il prêt ?\"}";

        mockMvc.perform(post("/api/entreprises/" + entrepriseTenantAId + "/copilot/question")
                        .header("Authorization", "Bearer " + jetonClientTenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/entreprises/" + entrepriseTenantAId + "/copilot-dirigeant/question")
                        .header("Authorization", "Bearer " + jetonClientTenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isOk());
    }

    @Test
    void expert_comptable_peut_appeler_le_tableau_de_bord_de_sa_propre_entreprise() throws Exception {
        mockMvc.perform(get("/api/entreprises/" + entrepriseTenantAId + "/tableau-de-bord")
                        .header("Authorization", "Bearer " + jetonExpertTenantA))
                .andExpect(status().isOk());
    }
}
