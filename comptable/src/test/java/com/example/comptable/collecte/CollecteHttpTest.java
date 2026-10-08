package com.example.comptable.collecte;

import com.example.comptable.collecte.delivery.DeliveryResult;
import com.example.comptable.collecte.service.CollecteService;
import com.example.comptable.collecte.service.DepotLienService;
import com.example.comptable.collecte.service.OutboundMessageWorker;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Securite HTTP de la collecte : routes internes reservees au cabinet (CLIENT 403, anonyme 401/403), et depot public par jeton
 * (seule porte anonyme : le jeton signe EST l'autorisation, relu contre la demande reelle).
 */
class CollecteHttpTest extends CollecteTestBase {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
            0x1F, 0x15, (byte) 0xC4, (byte) 0x89, 0, 0, 0, 0x0A, 'I', 'D', 'A', 'T', 0x78, (byte) 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtService jwtService;
    @Autowired CollecteService collecteService;
    @Autowired OutboundMessageWorker worker;
    @Autowired DepotLienService depotLienService;
    @Autowired DemandePieceRepository demandeRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void mock() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        jdbc.update("update outbound_message set status = 'CANCELLED' where status in ('PENDING', 'SENDING')");
    }

    private String jeton(UtilisateurPrincipal p) {
        return jwtService.generateToken(p);
    }

    private UtilisateurPrincipal client(Dossier d) {
        Utilisateur u = utilisateurRepository.findAll().stream()
                .filter(x -> d.telephone() != null && d.telephone().equals(x.getTelephone())).findFirst().orElseThrow();
        return new UtilisateurPrincipal(u);
    }

    private DemandePiece uneDemande(Dossier d) {
        acheterSansPiece(d, 4200);
        UUID finding = piecesManquantes(d).get(0).getId();
        UUID demandeId = collecteService.demander(d.id(), List.of(finding), d.expert()).demandeIds().get(0);
        return demandeRepository.findById(demandeId).orElseThrow();
    }

    private int statut(MockHttpServletRequestBuilder route, String bearer) throws Exception {
        MockHttpServletRequestBuilder requete = route.contentType(MediaType.APPLICATION_JSON).content("{}");
        if (bearer != null) {
            requete = requete.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(requete).andReturn().getResponse().getStatus();
    }

    private List<MockHttpServletRequestBuilder> routesInternes(UUID entrepriseId) {
        String base = "/api/entreprises/" + entrepriseId + "/demandes-pieces";
        UUID id = UUID.randomUUID();
        return List.of(get(base + "/collecte/synthese"), get(base + "/collecte/a-demander"), get(base + "/collecte/metriques"),
                get(base + "/collecte"), post(base + "/bulk"), get(base + "/" + id + "/messages"), post(base + "/" + id + "/envoyer"),
                post(base + "/" + id + "/relancer"), post(base + "/" + id + "/marquer-recue"), post(base + "/" + id + "/annuler"),
                get("/api/entreprises/" + entrepriseId + "/documents/" + id + "/demandes-candidates"),
                post("/api/entreprises/" + entrepriseId + "/documents/" + id + "/associer-demande"));
    }

    @Test
    void routesInternes_clientRecoit403_anonymeRefuse_cabinetJamaisBloque() throws Exception {
        Dossier d = nouveauDossier(true);
        // Les builders MockMvc sont mutables : chaque appel repart d'une liste neuve (sinon les en-tetes s'accumulent).
        int n = routesInternes(d.id()).size();
        for (int i = 0; i < n; i++) {
            String nom = routesInternes(d.id()).get(i).buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI();
            assertThat(statut(routesInternes(d.id()).get(i), jeton(client(d)))).as("CLIENT " + nom).isEqualTo(403);
            assertThat(statut(routesInternes(d.id()).get(i), null)).as("anonyme " + nom).isIn(401, 403);
            assertThat(statut(routesInternes(d.id()).get(i), jeton(d.expert()))).as("cabinet " + nom).isNotIn(401, 403);
        }
    }

    @Test
    void jetonValide_donneAccesAuxSeulesInformationsDeLaDemande_sansDonneeInterne() throws Exception {
        Dossier d = nouveauDossier(true);
        DemandePiece demande = uneDemande(d);

        String corps = mockMvc.perform(get("/api/depot/" + depotLienService.jeton(demande.getId())))
                .andExpect(jsonPath("$.entreprise").exists()).andExpect(jsonPath("$.montant").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(corps).doesNotContain(demande.getId().toString()).doesNotContain("findingId").doesNotContain("controlCode")
                .doesNotContain("ecritureId").doesNotContain(d.id().toString());
    }

    @Test
    void jetonAltereOuInconnu_estRefuseAvecLeMemeMessage() throws Exception {
        Dossier d = nouveauDossier(true);
        DemandePiece demande = uneDemande(d);
        String bon = depotLienService.jeton(demande.getId());
        String altere = bon.substring(0, bon.length() - 2) + (bon.endsWith("AA") ? "BB" : "AA");
        String autreId = depotLienService.jeton(UUID.randomUUID());
        String signatureDAutrui = bon.split("\\.")[0] + "." + autreId.split("\\.")[1];

        for (String mauvais : List.of(altere, autreId, signatureDAutrui, "n-importe-quoi", "a.b")) {
            assertThat(mockMvc.perform(get("/api/depot/" + mauvais)).andReturn().getResponse().getStatus()).as(mauvais).isEqualTo(404);
            assertThat(mockMvc.perform(multipart("/api/depot/" + mauvais).file(new MockMultipartFile("fichier", "f.png", "image/png", PNG)))
                    .andReturn().getResponse().getStatus()).as("depot " + mauvais).isEqualTo(404);
        }
    }

    @Test
    void jetonRefuseQuandLaDemandeEstAnnuleeResolueOuExpiree() throws Exception {
        Dossier d = nouveauDossier(true);
        DemandePiece annulee = uneDemande(d);
        collecteService.annuler(d.id(), annulee.getId(), d.expert());
        assertThat(mockMvc.perform(get("/api/depot/" + depotLienService.jeton(annulee.getId()))).andReturn().getResponse().getStatus()).isEqualTo(404);

        Dossier e = nouveauDossier(true);
        DemandePiece resolue = uneDemande(e);
        jdbc.update("update demande_piece set statut = 'RAPPROCHEE' where id = ?", resolue.getId());
        assertThat(mockMvc.perform(get("/api/depot/" + depotLienService.jeton(resolue.getId()))).andReturn().getResponse().getStatus()).isEqualTo(404);

        Dossier f = nouveauDossier(true);
        DemandePiece expiree = uneDemande(f);
        jdbc.update("update demande_piece set token_expire_at = now() - interval '1 day' where id = ?", expiree.getId());
        assertThat(mockMvc.perform(get("/api/depot/" + depotLienService.jeton(expiree.getId()))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(multipart("/api/depot/" + depotLienService.jeton(expiree.getId()))
                .file(new MockMultipartFile("fichier", "f.png", "image/png", PNG))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void depotValide_passeParLePipelineDocument_etLaDemandeNEstJamaisResolueSurSimpleDepot() throws Exception {
        Dossier d = nouveauDossier(true);
        when(fournisseur.send(any())).thenReturn(DeliveryResult.envoye("wamid"));
        DemandePiece demande = uneDemande(d);
        worker.traiterEnAttente();
        long avant = jdbc.queryForObject("select count(*) from document where entreprise_id = ?", Long.class, d.id());

        mockMvc.perform(multipart("/api/depot/" + depotLienService.jeton(demande.getId()))
                .file(new MockMultipartFile("fichier", "facture.png", "image/png", PNG))).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());

        assertThat(jdbc.queryForObject("select count(*) from document where entreprise_id = ?", Long.class, d.id())).isEqualTo(avant + 1);
        DemandePiece apres = demandeRepository.findById(demande.getId()).orElseThrow();
        assertThat(apres.getStatut()).isIn(StatutDemandePiece.RECU, StatutDemandePiece.RAPPROCHEE);
        assertThat(apres.getProchaineRelanceAt()).isNull();
        assertThat(evenementsPour("DOCUMENT_REQUEST_RECEIVED", demande.getId())).isGreaterThanOrEqualTo(1);
    }

    @Test
    void leClientNeVoitQueLaListeSimplifiee_sansIdentifiantInterne() throws Exception {
        Dossier d = nouveauDossier(true);
        DemandePiece demande = uneDemande(d);

        var reponse = mockMvc.perform(get("/api/entreprises/" + d.id() + "/demandes-pieces")
                        .header("Authorization", "Bearer " + jeton(client(d)))).andReturn().getResponse();
        assertThat(reponse.getStatus()).as(reponse.getErrorMessage()).isEqualTo(200);
        String corps = reponse.getContentAsString();
        assertThat(corps).contains("\"etat\":\"A_FOURNIR\"");

        assertThat(corps).doesNotContain("findingId").doesNotContain("controlCode").doesNotContain("ecritureId").doesNotContain("motifBlocage")
                .doesNotContain("nbRelances").doesNotContain("statut\"");
        assertThat(OffsetDateTime.now()).isAfter(demande.getCreatedAt());
    }

    @Test
    void leClientQuiRepond_nRecoitJamaisLeDtoComplet() throws Exception {
        Dossier d = nouveauDossier(true);
        DemandePiece demande = uneDemande(d);

        var reponse = mockMvc.perform(multipart("/api/entreprises/" + d.id() + "/demandes-pieces/" + demande.getId() + "/repondre")
                .file(new MockMultipartFile("fichier", "facture.png", "image/png", PNG)).param("typeDocument", "FACTURE_ACHAT")
                .header("Authorization", "Bearer " + jeton(client(d)))).andReturn().getResponse();

        assertThat(reponse.getStatus()).as(reponse.getContentAsString()).isEqualTo(200);
        assertThat(reponse.getContentAsString()).contains("\"etat\"").doesNotContain("findingId").doesNotContain("ecritureId")
                .doesNotContain("motifBlocage").doesNotContain("documentId");
    }

    @Test
    void envoyerEtRelancer_repondentEnJsonSansErreurDeSerialisation() throws Exception {
        Dossier d = nouveauDossier(true);
        DemandePiece demande = uneDemande(d);
        String base = "/api/entreprises/" + d.id() + "/demandes-pieces/" + demande.getId();

        var envoi = mockMvc.perform(post(base + "/envoyer").header("Authorization", "Bearer " + jeton(d.expert()))).andReturn().getResponse();
        assertThat(envoi.getStatus()).as(envoi.getContentAsString()).isEqualTo(200);
        assertThat(envoi.getContentAsString()).contains("\"statut\":\"A_ENVOYER\"").contains("\"demandeeParNom\"");

        // Rien n'est encore parti : la relance est refusee proprement (409), pas par une erreur serveur.
        assertThat(mockMvc.perform(post(base + "/relancer").header("Authorization", "Bearer " + jeton(d.expert()))).andReturn().getResponse().getStatus())
                .isEqualTo(409);
        assertThat(mockMvc.perform(post(base + "/marquer-recue").contentType(MediaType.APPLICATION_JSON).content("{\"commentaire\":\"recu par mail\"}")
                .header("Authorization", "Bearer " + jeton(d.expert()))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void entrepriseEtrangere_unCabinetNeLitPasLaCollecteDUnAutre() throws Exception {
        Dossier a = nouveauDossier(true);
        Dossier b = nouveauDossier(true);
        DemandePiece demande = uneDemande(a);

        int statut = statut(get("/api/entreprises/" + a.id() + "/demandes-pieces/" + demande.getId() + "/messages"), jeton(b.expert()));

        assertThat(statut).isIn(403, 404);
    }
}
