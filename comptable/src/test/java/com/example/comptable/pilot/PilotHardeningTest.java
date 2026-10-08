package com.example.comptable.pilot;

import com.example.comptable.cabinet.workspace.CabinetEquipeService;
import com.example.comptable.cabinet.workspace.CabinetEquipeService.MembreRequest;
import com.example.comptable.cabinet.support.SupportDiagnosticService;
import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.collecte.service.OutboundMessageWorker;
import com.example.comptable.common.web.CorrelationIdFilter;
import com.example.comptable.identite.service.AuthService;
import com.example.comptable.identite.service.CredentialService;
import com.example.comptable.identite.web.dto.AuthResponse;
import com.example.comptable.identite.web.dto.LoginRequest;
import com.example.comptable.identite.web.dto.RegisterCabinetRequest;
import com.example.comptable.security.RateLimiter;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Durcissement pilote sur une vraie base PostgreSQL : verrouillage de compte, mot de passe temporaire (changement obligatoire), réinitialisation à usage unique, révocation des jetons, désactivation immédiate,
 * inscription réservée à l'opérateur, politique de mot de passe, format d'erreur unique, identifiant de corrélation, sondes de santé, mode bac à sable des envois, diagnostic de support (isolé par cabinet).
 */
class PilotHardeningTest extends CollecteTestBase {

    @Autowired AuthService auth;
    @Autowired CredentialService credentials;
    @Autowired CabinetEquipeService equipe;
    @Autowired RateLimiter rateLimiter;
    @Autowired WebApplicationContext contexte;
    @Autowired CorrelationIdFilter correlationFilter;
    @Autowired SupportDiagnosticService support;

    private MockMvc mvc;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).addFilters(correlationFilter).apply(springSecurity()).build();
    }

    private static final String MDP = "Cabinet-Pilote-2026!";

    private record Cabinet(String email, AuthResponse expert, UtilisateurPrincipal principal) {
    }

    private Cabinet cabinet() {
        String email = "expert-" + UUID.randomUUID().toString().substring(0, 8) + "@pilote.test";
        AuthResponse r = auth.registerCabinet(new RegisterCabinetRequest("Cabinet Pilote " + email, "BF", email, MDP, "Expert", "Pilote", null));
        return new Cabinet(email, r, new UtilisateurPrincipal(utilisateurRepository.findByEmail(email).orElseThrow()));
    }

    private MockHttpServletResponse appeler(RequestBuilder r) throws Exception {
        return mvc.perform(r).andReturn().getResponse();
    }

    private static int statut(Throwable t) {
        return t instanceof ResponseStatusException r ? r.getStatusCode().value() : -1;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ verrouillage

    @Test
    void echecsRepetes_verrouillentLeCompte_reponseGeneriqueIdentique_etLeSuccesRemetLesCompteurs() {
        Cabinet c = cabinet();
        for (int i = 1; i <= 5; i++) {
            int essai = i;
            assertThatThrownBy(() -> auth.login(new LoginRequest(c.email(), "mauvais-" + essai), "ip-" + UUID.randomUUID())).hasMessageContaining("Identifiants invalides");
        }
        // le compte est verrouillé : même le BON mot de passe est refusé, avec un message générique (429)
        assertThatThrownBy(() -> auth.login(new LoginRequest(c.email(), MDP), "ip-" + UUID.randomUUID())).isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(statut(e)).isEqualTo(429));
        // un compte inconnu reçoit la même réponse qu'un mauvais mot de passe : aucune énumération
        assertThatThrownBy(() -> auth.login(new LoginRequest("inconnu-" + UUID.randomUUID() + "@pilote.test", MDP), "ip-" + UUID.randomUUID())).hasMessageContaining("Identifiants invalides");
        // fin du verrouillage (simulée) : la connexion réussit et remet les compteurs à zéro
        jdbc.update("update utilisateur set locked_until = now() - interval '1 minute' where email = ?", c.email());
        assertThat(auth.login(new LoginRequest(c.email(), MDP), "ip-" + UUID.randomUUID()).token()).isNotBlank();
        assertThat(jdbc.queryForObject("select failed_login_count from utilisateur where email = ?", Integer.class, c.email())).isZero();
    }

    @Test
    void debitParAdresse_limiteLesTentativesDeConnexion() {
        String ip = "ip-" + UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            rateLimiter.tenter("login|" + ip, 30, 600);
        }
        assertThatThrownBy(() -> auth.login(new LoginRequest("x@pilote.test", MDP), ip)).satisfies(e -> assertThat(statut(e)).isEqualTo(429));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ mot de passe temporaire

    @Test
    void motDePasseTemporaire_changementObligatoire_jetonAncienRevoque_etSeulLeChangementEstPermis() throws Exception {
        Cabinet c = cabinet();
        var cree = equipe.ajouter(c.principal(), new MembreRequest("collab-" + UUID.randomUUID().toString().substring(0, 8) + "@pilote.test", "Dupont", "Awa", "COLLABORATEUR", null));
        String email = cree.membre().email();
        String temporaire = cree.motDePasseTemporaire();
        assertThat(temporaire).isNotBlank();

        AuthResponse connexion = auth.login(new LoginRequest(email, temporaire), "ip-" + UUID.randomUUID());
        assertThat(connexion.mustChangePassword()).isTrue();
        String ancien = "Bearer " + connexion.token();

        // avec ce jeton, rien d'autre que le changement de mot de passe n'est possible
        MockHttpServletResponse bloque = appeler(get("/api/cabinet/equipe").header("Authorization", ancien));
        assertThat(bloque.getStatus()).isEqualTo(403);
        assertThat(bloque.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");

        // politique : trop court, ou identique à l'actuel
        String faible = "{\"motDePasseActuel\":\"" + temporaire + "\",\"nouveauMotDePasse\":\"court\"}";
        assertThat(appeler(post("/api/auth/change-password").header("Authorization", ancien).contentType(MediaType.APPLICATION_JSON).content(faible)).getStatus()).isEqualTo(422);
        String identique = "{\"motDePasseActuel\":\"" + temporaire + "\",\"nouveauMotDePasse\":\"" + temporaire + "\"}";
        assertThat(appeler(post("/api/auth/change-password").header("Authorization", ancien).contentType(MediaType.APPLICATION_JSON).content(identique)).getStatus()).isEqualTo(422);
        // sans authentification, la route est refusée
        assertThat(appeler(post("/api/auth/change-password").contentType(MediaType.APPLICATION_JSON).content(faible)).getStatus()).isIn(401, 403);

        String bon = "{\"motDePasseActuel\":\"" + temporaire + "\",\"nouveauMotDePasse\":\"Nouveau-Secret-2026\"}";
        MockHttpServletResponse ok = appeler(post("/api/auth/change-password").header("Authorization", ancien).contentType(MediaType.APPLICATION_JSON).content(bon));
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getContentAsString()).contains("\"mustChangePassword\":false");
        String nouveau = "Bearer " + ok.getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        assertThat(appeler(get("/api/cabinet/equipe").header("Authorization", nouveau)).getStatus()).isEqualTo(200);
        assertThat(appeler(get("/api/cabinet/equipe").header("Authorization", ancien)).getStatus()).as("l'ancien jeton est révoqué").isIn(401, 403);
        assertThatThrownBy(() -> auth.login(new LoginRequest(email, temporaire), "ip-" + UUID.randomUUID())).hasMessageContaining("Identifiants invalides");
        assertThat(auditEventRepository.findAll().stream().map(e -> e.getAction()).toList()).contains("MOT_DE_PASSE_CHANGE");
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ réinitialisation

    @Test
    void reinitialisation_jetonAUsageUnique_expiration_politique_tenantEtRole() {
        Cabinet c = cabinet();
        var membre = equipe.ajouter(c.principal(), new MembreRequest("reset-" + UUID.randomUUID().toString().substring(0, 8) + "@pilote.test", "Traore", "Moussa", "COLLABORATEUR", "Ancien-Secret-2026"));
        UUID id = membre.membre().id();
        String email = membre.membre().email();
        AuthResponse ancienneSession = auth.login(new LoginRequest(email, "Ancien-Secret-2026"), "ip-" + UUID.randomUUID());

        var emis = credentials.emettreReinitialisation(c.principal(), id);
        assertThat(emis.jeton()).hasSizeGreaterThan(30);
        assertThat(jdbc.queryForObject("select count(*) from password_reset_token where utilisateur_id = ? and token_hash = ?", Integer.class, id, emis.jeton())).as("seule l'empreinte est stockée").isZero();

        assertThatThrownBy(() -> credentials.reinitialiser(emis.jeton(), "court")).satisfies(e -> assertThat(statut(e)).isEqualTo(422));          // la politique s'applique, le jeton reste valide
        credentials.reinitialiser(emis.jeton(), "Reinitialise-2026-Ok");
        assertThat(auth.login(new LoginRequest(email, "Reinitialise-2026-Ok"), "ip-" + UUID.randomUUID()).mustChangePassword()).isFalse();
        assertThatThrownBy(() -> auth.login(new LoginRequest(email, "Ancien-Secret-2026"), "ip-" + UUID.randomUUID())).hasMessageContaining("Identifiants invalides");
        assertThat(utilisateurRepository.findByEmail(email).orElseThrow().getCredentialsVersion()).as("les sessions précédentes sont révoquées").isGreaterThan(0);
        assertThat(ancienneSession.token()).isNotBlank();

        // usage unique + réponse identique pour un jeton inconnu
        assertThatThrownBy(() -> credentials.reinitialiser(emis.jeton(), "Autre-Secret-2026-Ok")).hasMessageContaining("invalide ou expiré");
        assertThatThrownBy(() -> credentials.reinitialiser("jeton-inconnu", "Autre-Secret-2026-Ok")).hasMessageContaining("invalide ou expiré");

        // expiration
        var second = credentials.emettreReinitialisation(c.principal(), id);
        jdbc.update("update password_reset_token set expires_at = now() - interval '1 minute' where utilisateur_id = ? and used_at is null", id);
        assertThatThrownBy(() -> credentials.reinitialiser(second.jeton(), "Autre-Secret-2026-Ok")).hasMessageContaining("invalide ou expiré");

        // un nouveau jeton invalide le précédent
        var a = credentials.emettreReinitialisation(c.principal(), id);
        var b = credentials.emettreReinitialisation(c.principal(), id);
        assertThatThrownBy(() -> credentials.reinitialiser(a.jeton(), "Autre-Secret-2026-Ok")).hasMessageContaining("invalide ou expiré");
        credentials.reinitialiser(b.jeton(), "Dernier-Secret-2026-Ok");

        // un expert d'un AUTRE cabinet ne peut pas réinitialiser ce membre (404) ; un collaborateur ne le peut pas non plus (403)
        Cabinet autre = cabinet();
        assertThatThrownBy(() -> credentials.emettreReinitialisation(autre.principal(), id)).satisfies(e -> assertThat(statut(e)).isEqualTo(404));
        UtilisateurPrincipal collab = new UtilisateurPrincipal(utilisateurRepository.findByEmail(email).orElseThrow());
        assertThatThrownBy(() -> equipe.exigerExpertPourReinitialisation(collab)).satisfies(e -> assertThat(statut(e)).isEqualTo(403));
        assertThat(auditEventRepository.findAll().stream().map(e -> e.getAction()).toList()).contains("MOT_DE_PASSE_REINITIALISATION_EMISE", "MOT_DE_PASSE_REINITIALISE");
    }

    @Test
    void desactivation_estImmediate_leJetonEnCoursNeSertPlus() throws Exception {
        Cabinet c = cabinet();
        var membre = equipe.ajouter(c.principal(), new MembreRequest("off-" + UUID.randomUUID().toString().substring(0, 8) + "@pilote.test", "Kone", "Salif", "COLLABORATEUR", "Secret-Initial-2026"));
        String jeton = "Bearer " + auth.login(new LoginRequest(membre.membre().email(), "Secret-Initial-2026"), "ip-" + UUID.randomUUID()).token();
        assertThat(appeler(get("/api/cabinet/equipe").header("Authorization", jeton)).getStatus()).isEqualTo(200);
        jdbc.update("update utilisateur set statut = 'INACTIF' where id = ?", membre.membre().id());
        assertThat(appeler(get("/api/cabinet/equipe").header("Authorization", jeton)).getStatus()).isIn(401, 403);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ inscription

    @Test
    void inscriptionDeCabinet_reserveeALOperateurEnModeProvisioning_etPolitiqueDeMotDePasse() {
        String jeton = "jeton-operateur-" + UUID.randomUUID();
        Object modeInitial = ReflectionTestUtils.getField(auth, "registrationMode");
        Object jetonInitial = ReflectionTestUtils.getField(auth, "provisioningToken");
        try {
            ReflectionTestUtils.setField(auth, "registrationMode", "PROVISIONING");
            ReflectionTestUtils.setField(auth, "provisioningToken", jeton);
            String email = "prov-" + UUID.randomUUID().toString().substring(0, 8) + "@pilote.test";
            RegisterCabinetRequest req = new RegisterCabinetRequest("Cabinet Provisionné", "BF", email, MDP, "Expert", "Pilote", null);
            assertThatThrownBy(() -> auth.registerCabinet(req, null, "ip-" + UUID.randomUUID())).satisfies(e -> assertThat(statut(e)).isEqualTo(403));
            assertThatThrownBy(() -> auth.registerCabinet(req, "mauvais-jeton", "ip-" + UUID.randomUUID())).satisfies(e -> assertThat(statut(e)).isEqualTo(403));
            assertThat(utilisateurRepository.existsByEmail(email)).isFalse();
            assertThat(auth.registerCabinet(req, jeton, "ip-" + UUID.randomUUID()).roles()).contains("ROLE_EXPERT_COMPTABLE");

            // jeton d'approvisionnement non configuré : l'inscription est impossible même avec un en-tête
            ReflectionTestUtils.setField(auth, "provisioningToken", "");
            assertThatThrownBy(() -> auth.registerCabinet(req, "", "ip-" + UUID.randomUUID())).satisfies(e -> assertThat(statut(e)).isEqualTo(403));
        } finally {
            ReflectionTestUtils.setField(auth, "registrationMode", modeInitial);
            ReflectionTestUtils.setField(auth, "provisioningToken", jetonInitial);
        }
        RegisterCabinetRequest faible = new RegisterCabinetRequest("Cabinet Faible", "BF", "faible-" + UUID.randomUUID().toString().substring(0, 8) + "@pilote.test", "abcdefghijkl", "Expert", "Pilote", null);
        assertThatThrownBy(() -> auth.registerCabinet(faible)).satisfies(e -> assertThat(statut(e)).isEqualTo(422));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ erreurs, corrélation, santé

    @Test
    void formatDErreurUnique_correlationId_aucuneFuiteTechnique() throws Exception {
        // connexion refusée : code + message + correlationId, en-tête de réponse renvoyé
        MockHttpServletResponse refus = appeler(post("/api/auth/login").header(CorrelationIdFilter.EN_TETE, "req-pilote-0123456789").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"personne-" + UUID.randomUUID() + "@pilote.test\",\"motDePasse\":\"x\"}"));
        assertThat(refus.getStatus()).isEqualTo(401);
        assertThat(refus.getHeader(CorrelationIdFilter.EN_TETE)).isEqualTo("req-pilote-0123456789");
        String corps = refus.getContentAsString();
        assertThat(corps).contains("\"code\":\"UNAUTHORIZED\"").contains("\"message\":\"Identifiants invalides\"").contains("\"correlationId\":\"req-pilote-0123456789\"");

        // identifiant fourni douteux : remplacé par un identifiant généré
        MockHttpServletResponse douteux = appeler(post("/api/auth/login").header(CorrelationIdFilter.EN_TETE, "x\ny<script>").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.cd\",\"motDePasse\":\"x\"}"));
        assertThat(douteux.getHeader(CorrelationIdFilter.EN_TETE)).matches("[0-9a-f-]{36}");

        // validation : erreurs par champ, pas de classe d'exception
        MockHttpServletResponse invalide = appeler(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\",\"motDePasse\":\"\"}"));
        assertThat(invalide.getStatus()).isEqualTo(400);
        assertThat(invalide.getContentAsString()).contains("\"code\":\"VALIDATION_ERROR\"").contains("\"fieldErrors\":[{").contains("\"field\":\"email\"").doesNotContain("Exception").doesNotContain("org.springframework");

        // corps illisible
        MockHttpServletResponse illisible = appeler(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{pas du json"));
        assertThat(illisible.getStatus()).isEqualTo(400);
        assertThat(illisible.getContentAsString()).contains("\"code\":\"BAD_REQUEST\"").doesNotContain("JsonParse");

        // route inconnue authentifiée : 404 propre, jamais de trace
        Cabinet c = cabinet();
        MockHttpServletResponse inconnue = appeler(get("/api/route-qui-n-existe-pas").header("Authorization", "Bearer " + c.expert().token()));
        assertThat(inconnue.getStatus()).isEqualTo(404);
        assertThat(inconnue.getContentAsString()).doesNotContain("at com.example").doesNotContain("org.hibernate").doesNotContain("SQL");
    }

    @Test
    void sondesDeSante_etatSeulement_etActuatorNonExpose() throws Exception {
        MockHttpServletResponse sante = appeler(get("/actuator/health"));
        assertThat(sante.getStatus()).isEqualTo(200);
        assertThat(sante.getContentAsString()).contains("\"status\":\"UP\"").doesNotContain("jdbc:").doesNotContain("password").doesNotContain("\"details\"").doesNotContain("components");
        assertThat(appeler(get("/actuator/health/liveness")).getStatus()).isEqualTo(200);
        MockHttpServletResponse preparation = appeler(get("/actuator/health/readiness"));
        assertThat(preparation.getStatus()).isEqualTo(200);
        // les autres points d'actuator ne sont ni exposés ni publics
        assertThat(appeler(get("/actuator/env")).getStatus()).isIn(401, 403, 404);
        assertThat(appeler(get("/actuator/beans")).getStatus()).isIn(401, 403, 404);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ bac à sable, support

    @Test
    void modeBacASable_nAutoriseQueLesNumerosListes() {
        assertThat(OutboundMessageWorker.destinataireAutorise("", "22670000000")).as("vide : pas de restriction").isTrue();
        assertThat(OutboundMessageWorker.destinataireAutorise(null, "22670000000")).isTrue();
        assertThat(OutboundMessageWorker.destinataireAutorise("22670000000, +226 71 00 00 00", "22671000000")).isTrue();
        assertThat(OutboundMessageWorker.destinataireAutorise("22670000000", "22679999999")).isFalse();
        assertThat(OutboundMessageWorker.destinataireAutorise("22670000000", null)).isFalse();
        assertThat(OutboundMessageWorker.destinataireAutorise("000", "22670000000")).as("liste factice du staging : aucun vrai numéro").isFalse();
    }

    @Test
    void reinitialisationEtInscription_sontLimiteesEnDebitParAdresse_viaHttp() throws Exception {
        rateLimiter.reinitialiser("reset|127.0.0.1");
        rateLimiter.reinitialiser("register|127.0.0.1");
        try {
            for (int i = 0; i < 10; i++) {
                rateLimiter.tenter("reset|127.0.0.1", 10, 600);
                rateLimiter.tenter("register|127.0.0.1", 10, 3600);
            }
            MockHttpServletResponse reset = appeler(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON).content("{\"jeton\":\"x\",\"nouveauMotDePasse\":\"Nouveau-Secret-2026\"}"));
            assertThat(reset.getStatus()).isEqualTo(429);
            MockHttpServletResponse inscription = appeler(post("/api/auth/register-cabinet").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"raisonSocialeCabinet\":\"Cabinet Limite\",\"pays\":\"BF\",\"email\":\"limite@pilote.test\",\"motDePasse\":\"" + MDP + "\",\"nom\":\"A\",\"prenom\":\"B\",\"telephone\":null}"));
            assertThat(inscription.getStatus()).isEqualTo(429);
            assertThat(inscription.getContentAsString()).contains("TOO_MANY_REQUESTS");
        } finally {
            rateLimiter.reinitialiser("reset|127.0.0.1");
            rateLimiter.reinitialiser("register|127.0.0.1");
        }
    }

    @Test
    void lienDeDepotPublic_estLimiteEnDebit_etLesJetonsInventesRestentUniformes() throws Exception {
        String cleLecture = "depot-lecture|127.0.0.1";
        String cleEnvoi = "depot-envoi|127.0.0.1";
        rateLimiter.reinitialiser(cleLecture);
        rateLimiter.reinitialiser(cleEnvoi);
        try {
            for (int i = 0; i < 60; i++) {
                assertThat(mvc.perform(get("/api/depot/jeton-invente-" + i)).andReturn().getResponse().getStatus()).as("tentative " + i).isEqualTo(404);
            }
            MockHttpServletResponse trop = mvc.perform(get("/api/depot/jeton-invente-61")).andReturn().getResponse();
            assertThat(trop.getStatus()).isEqualTo(429);
            assertThat(trop.getContentAsString()).contains("TOO_MANY_REQUESTS").doesNotContain("Exception");
        } finally {
            rateLimiter.reinitialiser(cleLecture);
            rateLimiter.reinitialiser(cleEnvoi);
        }
    }

    @Test
    void diagnosticDeSupport_reserveAExpert_isoleParCabinet_sansTrace() {
        Dossier d = nouveauDossier(false);
        acheterSansPiece(d, 1000);
        var diagnostic = support.diagnostiquer(d.expert());
        assertThat(diagnostic.problemes()).extracting(SupportDiagnosticService.Probleme::code).contains("ENVOIS_EN_ECHEC", "PIECES_EN_ERREUR", "CONSTATS_BLOQUANTS", "COMPTES_VERROUILLES");
        assertThat(diagnostic.instance().derniereMigration()).isNotBlank();
        assertThat(diagnostic.toString()).doesNotContain("Exception").doesNotContain("password").doesNotContain("secret");

        // un autre cabinet ne voit rien de ce dossier
        Dossier autre = nouveauDossier(false);
        assertThat(support.diagnostiquer(autre.expert()).dossiers()).noneMatch(x -> x.entrepriseId().equals(d.entreprise().id()));

        // un collaborateur ou un client n'y a pas accès
        UtilisateurPrincipal collab = new UtilisateurPrincipal(utilisateur(tenantRepository.findById(d.expert().getTenantId()).orElseThrow(), "COLLABORATEUR", null));
        assertThatThrownBy(() -> support.diagnostiquer(collab)).satisfies(e -> assertThat(statut(e)).isEqualTo(403));
    }
}
