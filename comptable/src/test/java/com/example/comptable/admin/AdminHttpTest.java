package com.example.comptable.admin;

import com.example.comptable.collecte.CollecteTestBase;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Mode administrateur de la plateforme : accès réservé, gestion des comptes (motif, révocation des sessions, mot de passe temporaire), journal d'audit. Chemin HTTP réel, vraie base. */
class AdminHttpTest extends CollecteTestBase {

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;

    private MockMvc mvc;
    private Utilisateur admin;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        admin = utilisateur(null, "SUPER_ADMIN", null);
    }

    /** La base de test est durable et partagée avec le développement : un administrateur oublié empêcherait l'amorçage du premier administrateur local. On retire donc ceux que le test a créés. */
    @AfterEach
    void nettoyer() {
        String ids = "select u.id from utilisateur u join utilisateur_role ur on ur.utilisateur_id = u.id join role r on r.id = ur.role_id where r.code = 'SUPER_ADMIN' and u.email like 'collecte-%@test.ci'";
        jdbc.update("delete from audit_log where utilisateur_id in (" + ids + ")");
        jdbc.update("delete from utilisateur_role where utilisateur_id in (" + ids + ")");
        jdbc.update("delete from utilisateur where id in (select id from (" + ids + ") t)");
    }

    private String jeton(UtilisateurPrincipal p) {
        return "Bearer " + jwtService.generateToken(p);
    }

    private String jetonAdmin() {
        return jeton(new UtilisateurPrincipal(utilisateurRepository.findById(admin.getId()).orElseThrow()));
    }

    private MockHttpServletResponse lire(String url, String jeton) throws Exception {
        MockHttpServletRequestBuilder requete = get(url);
        if (jeton != null) {
            requete.header("Authorization", jeton);
        }
        return mvc.perform(requete).andReturn().getResponse();
    }

    private MockHttpServletResponse agir(UUID cible, String action, String motif) throws Exception {
        String corps = motif == null ? "{}" : "{\"motif\":\"" + motif + "\"}";
        return mvc.perform(post("/api/admin/utilisateurs/" + cible + "/" + action).contentType(MediaType.APPLICATION_JSON).content(corps).header("Authorization", jetonAdmin())).andReturn().getResponse();
    }

    @Test
    void lAdministration_estReserveeAuSuperAdmin() throws Exception {
        Dossier d = nouveauDossier(false);
        assertThat(lire("/api/admin/apercu", null).getStatus()).isEqualTo(401);
        assertThat(lire("/api/admin/apercu", jeton(d.expert())).getStatus()).as("un expert de cabinet n'entre pas").isEqualTo(403);
        assertThat(lire("/api/admin/utilisateurs", jeton(d.expert())).getStatus()).isEqualTo(403);
        assertThat(lire("/api/admin/audit", jeton(d.expert())).getStatus()).isEqualTo(403);
        assertThat(lire("/api/admin/apercu", jetonAdmin()).getStatus()).isEqualTo(200);
    }

    @Test
    void laListe_retrouveUnCompteAvecSonCabinet_sansAucunSecret() throws Exception {
        Dossier d = nouveauDossier(false);
        MockHttpServletResponse r = lire("/api/admin/utilisateurs?q=" + d.expert().getUsername(), jetonAdmin());
        assertThat(r.getStatus()).isEqualTo(200);
        String corps = r.getContentAsString();
        assertThat(corps).contains(d.expert().getUsername()).contains("Cabinet Collecte").contains("EXPERT_COMPTABLE");
        assertThat(corps).doesNotContain("\"motDePasse\"").doesNotContain("mot_de_passe").doesNotContain("$2a$").doesNotContain("\"hash\"");
        assertThat(lire("/api/admin/utilisateurs?statut=NIMPORTEQUOI", jetonAdmin()).getStatus()).isEqualTo(422);
    }

    @Test
    void suspendre_exigeUnMotif_coupeLesSessions_etEstTrace_puisReactiver() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = d.expert().getId();
        String jetonExpert = jeton(d.expert());
        assertThat(lire("/api/entreprises", jetonExpert).getStatus()).isEqualTo(200);

        assertThat(agir(id, "suspendre", null).getStatus()).as("sans motif").isEqualTo(422);
        assertThat(agir(id, "suspendre", "abc").getStatus()).as("motif trop court").isEqualTo(422);
        MockHttpServletResponse suspendu = agir(id, "suspendre", "Impayé de novembre");
        assertThat(suspendu.getStatus()).isEqualTo(200);
        assertThat(suspendu.getContentAsString()).contains("\"statut\":\"INACTIF\"");
        assertThat(lire("/api/entreprises", jetonExpert).getStatus()).as("la session ouverte est coupée tout de suite").isIn(401, 403);

        MockHttpServletResponse connexion = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + d.expert().getUsername() + "\",\"motDePasse\":\"nimporte-quel-mot-de-passe\"}")).andReturn().getResponse();
        assertThat(connexion.getStatus()).as("un compte suspendu est refusé proprement (401), jamais en erreur 500").isEqualTo(401);
        assertThat(connexion.getContentAsString()).contains("Identifiants invalides");

        long traces = jdbc.queryForObject("select count(*) from audit_log where action = 'ADMIN_UTILISATEUR_SUSPENDU' and entite_id = ? and utilisateur_id = ? and details::text like '%Impayé de novembre%'", Long.class,
                id.toString(), admin.getId());
        assertThat(traces).as("action tracée avec l'administrateur et le motif").isEqualTo(1);

        MockHttpServletResponse reactive = agir(id, "reactiver", "Régularisé");
        assertThat(reactive.getContentAsString()).contains("\"statut\":\"ACTIF\"");
    }

    @Test
    void reinitialiser_donneUnMotDePasseTemporaireUneSeuleFois_obligeAEnChoisirUnAutre() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = d.expert().getId();
        String ancienJeton = jeton(d.expert());

        MockHttpServletResponse r = agir(id, "reinitialiser-mot-de-passe", "Mot de passe oublié");
        assertThat(r.getStatus()).isEqualTo(200);
        String corps = r.getContentAsString();
        String temporaire = corps.replaceAll(".*\"motDePasseTemporaire\":\"([^\"]+)\".*", "$1");
        assertThat(temporaire).hasSize(16).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[0-9].*");
        assertThat(corps).contains("\"motDePasseAChanger\":true");
        assertThat(lire("/api/entreprises", ancienJeton).getStatus()).as("l'ancienne session est révoquée").isIn(401, 403);

        // la liste ne le relit jamais
        assertThat(lire("/api/admin/utilisateurs?q=" + d.expert().getUsername(), jetonAdmin()).getContentAsString()).doesNotContain(temporaire);

        // le mot de passe temporaire permet de se connecter, mais impose d'en choisir un autre
        MockHttpServletResponse connexion = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + d.expert().getUsername() + "\",\"motDePasse\":\"" + temporaire + "\"}"))
                .andReturn().getResponse();
        assertThat(connexion.getStatus()).isEqualTo(200);
        assertThat(connexion.getContentAsString()).contains("\"mustChangePassword\":true");
    }

    @Test
    void deverrouiller_remetLeCompteurAZero() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = d.expert().getId();
        jdbc.update("update utilisateur set failed_login_count = 3, locked_until = now() + interval '30 minutes' where id = ?", id);
        assertThat(lire("/api/admin/utilisateurs?statut=VERROUILLE&q=" + d.expert().getUsername(), jetonAdmin()).getContentAsString()).contains(d.expert().getUsername());

        MockHttpServletResponse r = agir(id, "deverrouiller", "Utilisateur bloqué au guichet");
        assertThat(r.getContentAsString()).contains("\"verrouille\":false").contains("\"echecsConsecutifs\":0");
        assertThat(lire("/api/admin/utilisateurs?statut=VERROUILLE&q=" + d.expert().getUsername(), jetonAdmin()).getContentAsString()).doesNotContain(d.expert().getUsername());
    }

    @Test
    void unCompteAdministrateur_neSeGerePasDepuisCetEcran_pasMemeSoi() throws Exception {
        Utilisateur autre = utilisateur(null, "SUPER_ADMIN", null);
        assertThat(agir(autre.getId(), "suspendre", "Tentative de prise de contrôle").getStatus()).isEqualTo(422);
        assertThat(agir(admin.getId(), "reinitialiser-mot-de-passe", "Sur moi-même").getStatus()).isEqualTo(422);
        assertThat(agir(UUID.randomUUID(), "suspendre", "Compte inexistant").getStatus()).isEqualTo(404);
    }

    @Test
    void leJournalGlobal_montreLesActionsDesCabinets_filtrable() throws Exception {
        Dossier d = nouveauDossier(false);
        agir(d.expert().getId(), "revoquer-sessions", "Poste perdu");

        MockHttpServletResponse r = lire("/api/admin/audit?action=ADMIN_SESSIONS&cabinetId=" + d.expert().getTenantId(), jetonAdmin());
        assertThat(r.getStatus()).isEqualTo(200);
        String corps = r.getContentAsString();
        assertThat(corps).contains("ADMIN_SESSIONS_REVOQUEES").contains("Poste perdu").contains("Cabinet Collecte").contains(admin.getEmail());
        assertThat(lire("/api/admin/audit?action=ADMIN_SESSIONS&cabinetId=" + UUID.randomUUID(), jetonAdmin()).getContentAsString()).doesNotContain("Poste perdu");
        assertThat(lire("/api/admin/audit?depuis=2999-01-01", jetonAdmin()).getContentAsString()).contains("\"total\":0");
    }
}
