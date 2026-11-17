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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Régulation des cabinets : liste, suspension appliquée à la connexion et aux sessions, quotas, abonnement, création. Chemin HTTP réel, vraie base. */
class AdminCabinetHttpTest extends CollecteTestBase {

    private static final String MOT_DE_PASSE = "Mot-de-passe-test-2026";

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder encodeur;

    private MockMvc mvc;
    private Utilisateur admin;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        admin = utilisateur(null, "SUPER_ADMIN", null);
    }

    /** Même raison que dans AdminHttpTest : la base de test est durable et partagée avec le développement. */
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

    private MockHttpServletResponse envoyer(String url, String corps, String jeton) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(corps).header("Authorization", jeton)).andReturn().getResponse();
    }

    private MockHttpServletResponse agir(UUID cabinet, String action, String motif) throws Exception {
        return envoyer("/api/admin/cabinets/" + cabinet + "/" + action, "{\"motif\":\"" + motif + "\"}", jetonAdmin());
    }

    private MockHttpServletResponse abonnement(UUID cabinet, String corps) throws Exception {
        return envoyer("/api/admin/cabinets/" + cabinet + "/abonnement", corps, jetonAdmin());
    }

    private MockHttpServletResponse connexion(String email, String motDePasse) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"motDePasse\":\"" + motDePasse + "\"}")).andReturn().getResponse();
    }

    /** L'expert d'un dossier de test a un « mot de passe » factice : on lui en donne un vrai pour tester la connexion. */
    private void donnerUnVraiMotDePasse(Dossier d) {
        jdbc.update("update utilisateur set mot_de_passe = ? where id = ?", encodeur.encode(MOT_DE_PASSE), d.expert().getId());
    }

    @Test
    void lesCabinets_sontReservesAuSuperAdmin_etListentCeQuIlsConsomment() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID cabinet = d.expert().getTenantId();
        assertThat(lire("/api/admin/cabinets", null).getStatus()).isEqualTo(401);
        assertThat(lire("/api/admin/cabinets", jeton(d.expert())).getStatus()).isEqualTo(403);
        assertThat(envoyer("/api/admin/cabinets", "{}", jeton(d.expert())).getStatus()).isEqualTo(403);
        assertThat(agir(cabinet, "suspendre", "Tentative sans droit").getStatus()).as("l'administrateur seul").isEqualTo(200);

        MockHttpServletResponse r = lire("/api/admin/cabinets?q=cabinet-collecte", jetonAdmin());
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentAsString()).contains("Cabinet Collecte").contains("\"membresActifs\":1").contains("\"dossiersActifs\":1").contains("\"plan\":\"ESSAI\"");
        assertThat(lire("/api/admin/cabinets?statut=NIMPORTEQUOI", jetonAdmin()).getStatus()).isEqualTo(422);
    }

    @Test
    void suspendreUnCabinet_coupeLesSessions_refuseLaConnexionAvecUnMessageClair_puisReactiver() throws Exception {
        Dossier d = nouveauDossier(false);
        donnerUnVraiMotDePasse(d);
        UUID cabinet = d.expert().getTenantId();
        String jetonExpert = jeton(d.expert());
        assertThat(lire("/api/entreprises", jetonExpert).getStatus()).isEqualTo(200);
        assertThat(connexion(d.expert().getUsername(), MOT_DE_PASSE).getStatus()).isEqualTo(200);

        assertThat(agir(cabinet, "suspendre", "abc").getStatus()).as("motif trop court").isEqualTo(422);
        MockHttpServletResponse suspendu = agir(cabinet, "suspendre", "Abonnement impayé");
        assertThat(suspendu.getStatus()).isEqualTo(200);
        assertThat(suspendu.getContentAsString()).contains("\"statut\":\"SUSPENDU\"").contains("Abonnement impayé");

        assertThat(lire("/api/entreprises", jetonExpert).getStatus()).as("la session ouverte est refusée tout de suite").isIn(401, 403);
        MockHttpServletResponse refus = connexion(d.expert().getUsername(), MOT_DE_PASSE);
        assertThat(refus.getStatus()).isEqualTo(403);
        assertThat(refus.getContentAsString()).contains("suspendu");
        MockHttpServletResponse mauvais = connexion(d.expert().getUsername(), "mauvais-mot-de-passe-1");
        assertThat(mauvais.getStatus()).as("sans le bon mot de passe, rien n'est révélé sur l'état du cabinet").isEqualTo(401);
        assertThat(mauvais.getContentAsString()).doesNotContain("suspendu");

        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'ADMIN_CABINET_SUSPENDU' and tenant_id = ? and utilisateur_id = ?", Long.class, cabinet, admin.getId())).isEqualTo(1);
        assertThat(lire("/api/admin/apercu", jetonAdmin()).getContentAsString()).doesNotContain("\"cabinetsSuspendus\":0");

        MockHttpServletResponse reactive = agir(cabinet, "reactiver", "Paiement reçu");
        assertThat(reactive.getContentAsString()).contains("\"statut\":\"ACTIF\"").contains("\"motifSuspension\":null");
        assertThat(connexion(d.expert().getUsername(), MOT_DE_PASSE).getStatus()).isEqualTo(200);
        assertThat(lire("/api/entreprises", jetonExpert).getStatus()).as("la session d'avant la suspension redevient valable").isEqualTo(200);
    }

    @Test
    void leQuotaDUtilisateurs_refuseLAjoutAuDela_sansToucherAuxMembresExistants() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID cabinet = d.expert().getTenantId();
        String corps = "{\"email\":\"membre-" + UUID.randomUUID() + "@test.ci\",\"nom\":\"Traore\",\"prenom\":\"Issa\",\"role\":\"COLLABORATEUR\"}";

        assertThat(abonnement(cabinet, "{\"plan\":\"STANDARD\",\"quotaUtilisateurs\":1,\"motif\":\"Plan de départ\"}").getStatus()).isEqualTo(200);
        MockHttpServletResponse refus = envoyer("/api/cabinet/membres", corps, jeton(d.expert()));
        assertThat(refus.getStatus()).isEqualTo(403);
        assertThat(refus.getContentAsString()).contains("limité à 1").contains("Contactez l'équipe Warimara");

        assertThat(abonnement(cabinet, "{\"plan\":\"STANDARD\",\"quotaUtilisateurs\":2,\"motif\":\"Extension demandée\"}").getStatus()).isEqualTo(200);
        assertThat(envoyer("/api/cabinet/membres", corps, jeton(d.expert())).getStatus()).isEqualTo(201);
        assertThat(envoyer("/api/cabinet/membres", corps.replaceAll("membre-[0-9a-f-]+", "autre-" + UUID.randomUUID()), jeton(d.expert())).getStatus()).as("le deuxième ajout dépasse").isEqualTo(403);
    }

    @Test
    void leQuotaDeDossiers_refuseUneNouvelleEntreprise() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID cabinet = d.expert().getTenantId();
        assertThat(abonnement(cabinet, "{\"plan\":\"STANDARD\",\"quotaDossiers\":1,\"motif\":\"Un dossier inclus\"}").getStatus()).isEqualTo(200);
        MockHttpServletResponse refus = envoyer("/api/entreprises", "{\"raisonSociale\":\"Deuxieme PME\",\"pays\":\"CI\",\"devise\":\"XOF\"}", jeton(d.expert()));
        assertThat(refus.getStatus()).isEqualTo(403);
        assertThat(refus.getContentAsString()).contains("limité à 1 dossier");
        assertThat(abonnement(cabinet, "{\"plan\":\"STANDARD\",\"quotaDossiers\":null,\"motif\":\"Limite levée\"}").getStatus()).isEqualTo(200);
        assertThat(envoyer("/api/entreprises", "{\"raisonSociale\":\"Deuxieme PME\",\"pays\":\"CI\",\"devise\":\"XOF\"}", jeton(d.expert())).getStatus()).isEqualTo(201);
    }

    @Test
    void labonnement_valideSesEntrees_etTraceLAvantEtLApres() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID cabinet = d.expert().getTenantId();
        assertThat(abonnement(cabinet, "{\"plan\":\"STANDARD\"}").getStatus()).as("sans motif").isEqualTo(422);
        assertThat(abonnement(cabinet, "{\"plan\":\"GOLD\",\"motif\":\"Plan inconnu\"}").getStatus()).isEqualTo(422);
        assertThat(abonnement(cabinet, "{\"plan\":\"STANDARD\",\"quotaDossiers\":0,\"motif\":\"Quota nul\"}").getStatus()).isEqualTo(422);
        assertThat(abonnement(UUID.randomUUID(), "{\"plan\":\"STANDARD\",\"motif\":\"Cabinet inexistant\"}").getStatus()).isEqualTo(404);

        MockHttpServletResponse ok = abonnement(cabinet, "{\"plan\":\"PARTENAIRE\",\"finEssai\":null,\"quotaUtilisateurs\":5,\"quotaDossiers\":20,\"motif\":\"Accord partenaire\"}");
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getContentAsString()).contains("\"plan\":\"PARTENAIRE\"").contains("\"quotaUtilisateurs\":5").contains("\"quotaDossiers\":20");
        String trace = jdbc.queryForObject("select details::text from audit_log where action = 'ADMIN_ABONNEMENT_MODIFIE' and tenant_id = ? order by id desc limit 1", String.class, cabinet);
        assertThat(trace).contains("Accord partenaire").contains("ESSAI").contains("PARTENAIRE");
    }

    @Test
    void unEssaiDepasse_estSignale_maisJamaisCoupeAutomatiquement() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID cabinet = d.expert().getTenantId();
        jdbc.update("update tenant set fin_essai = current_date - 3 where id = ?", cabinet);

        MockHttpServletResponse r = lire("/api/admin/cabinets?statut=ESSAI_EXPIRE&q=cabinet-collecte", jetonAdmin());
        assertThat(r.getContentAsString()).contains("\"essaiExpire\":true");
        assertThat(lire("/api/admin/apercu", jetonAdmin()).getContentAsString()).doesNotContain("\"essaisExpires\":0");
        assertThat(lire("/api/entreprises", jeton(d.expert())).getStatus()).as("l'accès n'est pas coupé : c'est l'administrateur qui décide").isEqualTo(200);
    }

    @Test
    void creerUnCabinet_donneUnMotDePasseTemporaireUneSeuleFois_etUnEssaiDe30Jours() throws Exception {
        String email = "expert-" + UUID.randomUUID() + "@nouveau-cabinet.test";
        String corps = "{\"raisonSociale\":\"Cabinet Nouveau Test\",\"pays\":\"bf\",\"emailExpert\":\"" + email + "\",\"nom\":\"Ouedraogo\",\"prenom\":\"Awa\"}";

        MockHttpServletResponse r = envoyer("/api/admin/cabinets", corps, jetonAdmin());
        assertThat(r.getStatus()).isEqualTo(201);
        String reponse = r.getContentAsString();
        assertThat(reponse).contains("\"plan\":\"ESSAI\"").contains("\"pays\":\"BF\"").contains("\"membresActifs\":1");
        String temporaire = reponse.replaceAll(".*\"motDePasseTemporaire\":\"([^\"]+)\".*", "$1");
        assertThat(temporaire).hasSize(16);
        // comparé à la date du serveur Java (et non à current_date de la base, en UTC) : entre minuit et 1 h en heure locale, les deux jours diffèrent
        assertThat(jdbc.queryForObject("select fin_essai from tenant where raison_sociale = 'Cabinet Nouveau Test' order by created_at desc limit 1", java.time.LocalDate.class)).isEqualTo(java.time.LocalDate.now().plusDays(30));

        MockHttpServletResponse connexion = connexion(email, temporaire);
        assertThat(connexion.getStatus()).isEqualTo(200);
        assertThat(connexion.getContentAsString()).contains("\"mustChangePassword\":true").contains("ROLE_EXPERT_COMPTABLE");
        assertThat(lire("/api/admin/cabinets?q=nouveau", jetonAdmin()).getContentAsString()).doesNotContain(temporaire);

        assertThat(envoyer("/api/admin/cabinets", corps, jetonAdmin()).getStatus()).as("même e-mail").isEqualTo(409);
        assertThat(envoyer("/api/admin/cabinets", corps.replace("\"bf\"", "\"burkina\""), jetonAdmin()).getStatus()).isEqualTo(422);
        assertThat(envoyer("/api/admin/cabinets", corps.replace(email, "pas-un-email"), jetonAdmin()).getStatus()).isEqualTo(422);
    }
}
