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
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Mission P1.9 (Phase 8, "corriger les vrais bugs, pas seulement les documenter") : les deux
 * nouveaux endpoints de ce chantier ({@code GET /api/cabinet/kpi},
 * {@code POST /api/copilot/queries/{id}/feedback}) etaient couverts par des tests de service
 * (unitaires) mais jamais par un test HTTP reel comme {@link P1_5SecuriteHttpE2ETest} l'a fait
 * pour P1.5 - gap confirme en ecrivant ce test, ferme ici plutot que documente comme restant.
 * Meme pattern exact : MockMvc construit manuellement + vraie chaine de securite + vrais jetons
 * JWT + {@code spring.docker.compose.skip.in-tests=false} explicite.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class P1_9SecuriteHttpE2ETest {

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
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiServiceClient aiServiceClient;

    private String jetonExpertTenantA;
    private String jetonClientTenantA;
    private UUID entrepriseTenantAId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();

        when(aiServiceClient.copilotExplain(any())).thenReturn(
                Optional.of(new CopilotExplainResponseDTO("Reponse de test.")));

        Role roleExpert = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Role roleClient = roleRepository.findByCode("CLIENT").orElseThrow();

        Tenant tenantA = nouveauTenant("Cabinet P1.9 A");
        Utilisateur expertA = nouvelUtilisateur(tenantA, roleExpert);
        UtilisateurPrincipal principalExpert = new UtilisateurPrincipal(expertA);
        jetonExpertTenantA = jwtService.generateToken(principalExpert);

        Utilisateur clientA = nouvelUtilisateur(tenantA, roleClient);
        jetonClientTenantA = jwtService.generateToken(new UtilisateurPrincipal(clientA));

        EntrepriseDto entrepriseA = entrepriseService.creer(
                new CreerEntrepriseRequest("SARL Tenant P1.9 A", null, null, null, null, null, "BF", "XOF"),
                principalExpert);
        entrepriseTenantAId = entrepriseA.id();
        utilisateurEntrepriseRepository.save(new UtilisateurEntreprise(clientA.getId(), entrepriseTenantAId));
    }

    private Tenant nouveauTenant(String nom) {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale(nom);
        tenant.setSlug(nom.toLowerCase().replace(" ", "-").replace(".", "") + "-" + UUID.randomUUID());
        tenant.setPays("BF");
        return tenantRepository.save(tenant);
    }

    private Utilisateur nouvelUtilisateur(Tenant tenant, Role role) {
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("p1-9-http-" + UUID.randomUUID() + "@test.bf");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom(role.getCode());
        utilisateur.setRoles(Set.of(role));
        return utilisateurRepository.save(utilisateur);
    }

    @Test
    void client_ne_peut_pas_appeler_le_tableau_de_bord_qualite_du_cabinet() throws Exception {
        mockMvc.perform(get("/api/cabinet/kpi")
                        .header("Authorization", "Bearer " + jetonClientTenantA))
                .andExpect(status().isForbidden());
    }

    @Test
    void expert_comptable_peut_appeler_le_tableau_de_bord_qualite_de_son_cabinet() throws Exception {
        mockMvc.perform(get("/api/cabinet/kpi")
                        .header("Authorization", "Bearer " + jetonExpertTenantA))
                .andExpect(status().isOk());
    }

    @Test
    void requete_de_feedback_sans_jeton_est_rejetee() throws Exception {
        mockMvc.perform(post("/api/copilot/queries/" + UUID.randomUUID() + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"POSITIVE\",\"reason\":null}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dirigeant_peut_poser_une_question_puis_laisser_un_feedback_sur_sa_propre_reponse() throws Exception {
        String reponseBrute = mockMvc.perform(post("/api/entreprises/" + entrepriseTenantAId + "/copilot-dirigeant/question")
                        .header("Authorization", "Bearer " + jetonClientTenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Mon dossier est-il prêt ?\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String queryId = objectMapper.readTree(reponseBrute).get("queryId").asText();

        mockMvc.perform(post("/api/copilot/queries/" + queryId + "/feedback")
                        .header("Authorization", "Bearer " + jetonClientTenantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"POSITIVE\",\"reason\":null}"))
                .andExpect(status().isNoContent());
    }
}
