package com.example.comptable.etatsfinanciers;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.CashFlowEvent;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.ApplicabilityPatch;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Le dossier financier vu de l'API HTTP reelle. Ce test existe parce que le navigateur a revele que le contenu d'un snapshot (un {@code JsonNode} Jackson 2) etait serialise comme un
 * bean (getters array, bigDecimal...) par le serialiseur HTTP : invisible pour les tests de service. Il verrouille aussi les roles (CLIENT refuse sur toute la route, COLLABORATEUR
 * refuse a la finalisation) et le multi-tenant (404).
 */
class FinancialDossierHttpTest extends BankTestBase {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtService jwtService;
    @Autowired NoteWorkspaceService workspace;
    @Autowired EcritureComptableService ecritureService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired ExerciceComptableRepository exerciceRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    private String jeton(UtilisateurPrincipal p) {
        return "Bearer " + jwtService.generateToken(p);
    }

    private UtilisateurPrincipal utilisateurDuCabinet(Dossier d, String role, boolean rattacher) {
        Tenant tenant = tenantRepository.findById(d.expert().getTenantId()).orElseThrow();
        Utilisateur u = utilisateur(tenant, role, null);
        if (rattacher) {
            utilisateurEntrepriseRepository.save(new UtilisateurEntreprise(u.getId(), d.id()));
        }
        return new UtilisateurPrincipal(u);
    }

    private UUID compte(Dossier d, String prefixe) {
        String numero = prefixe + "900";
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseGet(() -> {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(d.id()));
            c.setNumero(numero);
            c.setIntitule("Compte de test " + numero);
            c.setLettrable(false);
            return compteComptableRepository.save(c);
        }).getId();
    }

    private String base(Dossier d) {
        return "/api/entreprises/" + d.id() + "/etats-financiers-syscohada";
    }

    private UUID exercice(Dossier d) {
        return exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
    }

    private UUID capital(Dossier d) {
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        lignes.add(new LigneEcritureRequest(compte(d, "521"), "D", new BigDecimal(700), BigDecimal.ZERO, null));
        lignes.add(new LigneEcritureRequest(compte(d, "101"), "C", BigDecimal.ZERO, new BigDecimal(700), null));
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, LocalDate.now(), "Capital", null, lignes, CashFlowEvent.CAPITAL_CASH_INCREASE), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
        return id;
    }

    @Test
    void synthese_enJson_etRolesHttp() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        capital(d);

        MockHttpServletResponse r = mockMvc.perform(get(base(d) + "/dossier-financier").param("exerciceId", ex.toString()).header("Authorization", jeton(d.expert()))).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentAsString()).contains("\"finalizable\":false").contains("\"exceptions\"").contains("EXERCICE_OUVERT").contains("\"permissions\"");

        // CLIENT : aucun acces a la route des etats financiers (comme l'ancien reporting) ; COLLABORATEUR : lecture autorisee
        UtilisateurPrincipal client = utilisateurDuCabinet(d, "CLIENT", true);
        assertThat(mockMvc.perform(get(base(d) + "/dossier-financier").param("exerciceId", ex.toString()).header("Authorization", jeton(client))).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mockMvc.perform(put(base(d) + "/ecritures/" + UUID.randomUUID() + "/cash-flow-event").contentType("application/json").content("{\"event\":null}")
                .header("Authorization", jeton(client))).andReturn().getResponse().getStatus()).isEqualTo(403);
        UtilisateurPrincipal collaborateur = utilisateurDuCabinet(d, "COLLABORATEUR", false);
        assertThat(mockMvc.perform(get(base(d) + "/dossier-financier").param("exerciceId", ex.toString()).header("Authorization", jeton(collaborateur))).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        // autre tenant : 404
        Dossier autre = nouveauDossier(false);
        assertThat(mockMvc.perform(get(base(d) + "/dossier-financier").param("exerciceId", ex.toString()).header("Authorization", jeton(autre.expert()))).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void finalisation_snapshotEnJsonNatif_exports_et_roleDeFinalisation() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        capital(d);
        workspace.validerCandidates(d.id(), ex, d.expert());
        for (var n : workspace.lister(d.id(), ex, d.expert()).notes()) {
            if (n.applicabilityStatus().equals("TO_DETERMINE")) {
                workspace.modifierApplicabilite(d.id(), ex, n.noteCode(), new ApplicabilityPatch(n.version(), "NOT_APPLICABLE", "Sans objet"), d.expert());
            }
        }
        clotureService.cloturerAvecConfirmation(d.id(), ex, d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test HTTP"));

        UtilisateurPrincipal collaborateur = utilisateurDuCabinet(d, "COLLABORATEUR", false);
        assertThat(mockMvc.perform(post(base(d) + "/DOSSIER_COMPLET/finaliser").param("exerciceId", ex.toString()).header("Authorization", jeton(collaborateur))).andReturn().getResponse()
                .getStatus()).isEqualTo(403);

        MockHttpServletResponse final1 = mockMvc.perform(post(base(d) + "/DOSSIER_COMPLET/finaliser").param("exerciceId", ex.toString()).header("Authorization", jeton(d.expert())))
                .andReturn().getResponse();
        assertThat(final1.getStatus()).isEqualTo(200);
        String json = final1.getContentAsString();
        // contenu du snapshot en JSON natif (et non en bean Jackson 2 : getters array, bigDecimal...)
        assertThat(json).contains("\"frameworkVersion\":\"SYSCOHADA_REVISE_AUDCIF_2017/2\"").contains("\"compteResultat\"").contains("\"definitionHashes\"").doesNotContain("\"bigDecimal\"");
        String id = json.replaceAll(".*?\"id\":\"([0-9a-f-]{36})\".*", "$1");

        // double finalisation : refusee (409) ; role COLLABORATEUR toujours refuse
        assertThat(mockMvc.perform(post(base(d) + "/DOSSIER_COMPLET/finaliser").param("exerciceId", ex.toString()).header("Authorization", jeton(d.expert()))).andReturn().getResponse()
                .getStatus()).isEqualTo(409);

        MockHttpServletResponse lu = mockMvc.perform(get(base(d) + "/snapshots/" + id).header("Authorization", jeton(d.expert()))).andReturn().getResponse();
        assertThat(lu.getContentAsString()).contains("\"frameworkVersion\"").contains("\"tft\"").contains("\"notes\"");

        MockHttpServletResponse exportFinal = mockMvc.perform(get(base(d) + "/snapshots/" + id + "/export").header("Authorization", jeton(d.expert()))).andReturn().getResponse();
        assertThat(exportFinal.getContentType()).startsWith("text/html");
        assertThat(exportFinal.getContentAsString()).contains("DOSSIER FINANCIER FINAL").doesNotContain("class=\"filigrane\"");
        MockHttpServletResponse brouillon = mockMvc.perform(get(base(d) + "/dossier-financier/export").param("exerciceId", ex.toString()).header("Authorization", jeton(d.expert())))
                .andReturn().getResponse();
        assertThat(brouillon.getContentAsString()).contains("BROUILLON").contains("filigrane");

        // autre tenant : 404 sur le snapshot et son export
        Dossier autre = nouveauDossier(false);
        assertThat(mockMvc.perform(get(base(d) + "/snapshots/" + id).header("Authorization", jeton(autre.expert()))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get(base(d) + "/snapshots/" + id + "/export").header("Authorization", jeton(autre.expert()))).andReturn().getResponse().getStatus()).isEqualTo(404);
        // toutes les autres routes du dossier : 404 pour un autre tenant
        String exercice = "exerciceId=" + ex;
        assertThat(mockMvc.perform(put(base(d) + "/ecritures/" + UUID.randomUUID() + "/cash-flow-event").contentType("application/json").content("{\"event\":null}")
                .header("Authorization", jeton(autre.expert()))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get(base(d) + "/notes").param("exerciceId", ex.toString()).header("Authorization", jeton(autre.expert()))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get(base(d) + "/notes/17").param("exerciceId", ex.toString()).header("Authorization", jeton(autre.expert()))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(post(base(d) + "/notes/17/review").param("exerciceId", ex.toString()).header("Authorization", jeton(autre.expert()))).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mockMvc.perform(post(base(d) + "/notes/17/reopen").param("exerciceId", ex.toString()).header("Authorization", jeton(autre.expert()))).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mockMvc.perform(post(base(d) + "/DOSSIER_COMPLET/finaliser").param("exerciceId", ex.toString()).header("Authorization", jeton(autre.expert()))).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get(base(d) + "/cash-flow-events").header("Authorization", jeton(autre.expert()))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(exercice).isNotBlank();
    }

    @Test
    void contratsHttp_variationBackend_conflitDeVersion409_et_404AutreTenantSurTousLesEtats() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID ex = exercice(d);
        capital(d);
        String jetonExpert = jeton(d.expert());

        // la variation N - N-1 est calculee par le backend (jamais par Angular) et exposee dans chaque ligne
        String cr = mockMvc.perform(get(base(d) + "/compte-resultat").param("exerciceId", ex.toString()).header("Authorization", jetonExpert)).andReturn().getResponse().getContentAsString();
        assertThat(cr).contains("\"variation\"");

        // optimistic locking : une version perimee est refusee en 409 (jamais ecrasee silencieusement)
        String note = mockMvc.perform(get(base(d) + "/notes/17").param("exerciceId", ex.toString()).header("Authorization", jetonExpert)).andReturn().getResponse().getContentAsString();
        int version = Integer.parseInt(note.replaceAll(".*?\"version\":(\\d+).*", "$1"));
        String section = note.replaceAll(".*?\"manualSections\":\\[\\{\"sectionCode\":\"([^\"]+)\".*", "$1");
        String corps = "{\"expectedVersion\":%d,\"fields\":[{\"sectionCode\":\"%s\",\"fieldCode\":\"contenu\",\"value\":\"Texte\"}]}";
        assertThat(mockMvc.perform(patch(base(d) + "/notes/17/manual-data").param("exerciceId", ex.toString()).contentType("application/json").content(corps.formatted(version, section))
                .header("Authorization", jetonExpert)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(patch(base(d) + "/notes/17/manual-data").param("exerciceId", ex.toString()).contentType("application/json").content(corps.formatted(version, section))
                .header("Authorization", jetonExpert)).andReturn().getResponse().getStatus()).isEqualTo(409);

        // autre tenant : 404 sur les etats vivants, le drill-down, les exports et les ecritures de Notes
        Dossier autre = nouveauDossier(false);
        String jetonAutre = jeton(autre.expert());
        for (String chemin : List.of("/compte-resultat", "/bilan", "/tft", "/COMPTE_RESULTAT/lignes/XA", "/dossier-financier/export")) {
            assertThat(mockMvc.perform(get(base(d) + chemin).param("exerciceId", ex.toString()).header("Authorization", jetonAutre)).andReturn().getResponse().getStatus()).as(chemin).isEqualTo(404);
        }
        assertThat(mockMvc.perform(post(base(d) + "/notes/validate-candidates").param("exerciceId", ex.toString()).header("Authorization", jetonAutre)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(patch(base(d) + "/notes/17/manual-data").param("exerciceId", ex.toString()).contentType("application/json").content(corps.formatted(version, section))
                .header("Authorization", jetonAutre)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(patch(base(d) + "/notes/17/applicability").param("exerciceId", ex.toString()).contentType("application/json")
                .content("{\"expectedVersion\":1,\"status\":\"APPLICABLE\"}").header("Authorization", jetonAutre)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
