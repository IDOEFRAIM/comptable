package com.example.comptable.pilot;

import com.example.comptable.collecte.CollecteTestBase;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Une opération saisie hors ligne (Saisie rapide) est envoyée à la synchronisation : une proposition est générée (jamais deux, même si l'envoi est repris après une coupure), puis retrouvée dans la liste des
 * propositions en attente pour être validée en ligne. Chemin HTTP réel, vraie base.
 */
class OperationHorsLigneHttpTest extends CollecteTestBase {

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;

    private MockMvc mvc;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    private String corpsAchat(Dossier d, String libelle) {
        UUID compteCharge = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "601").orElseThrow().getId();
        return "{\"natureAchat\":\"MARCHANDISE\",\"fournisseurId\":\"" + d.fournisseur().id() + "\",\"date\":\"" + LocalDate.now() + "\",\"reference\":\"F-1\","
                + "\"libelle\":\"" + libelle + "\",\"montantHt\":45000,\"compteChargeId\":\"" + compteCharge + "\"}";
    }

    private MockHttpServletResponse proposer(Dossier d, String corps, String cle) throws Exception {
        var requete = post("/api/entreprises/" + d.id() + "/operations/achat/proposition").contentType(MediaType.APPLICATION_JSON).content(corps)
                .header("Authorization", "Bearer " + jwtService.generateToken(d.expert()));
        if (cle != null) {
            requete.header("Idempotency-Key", cle);
        }
        return mvc.perform(requete).andReturn().getResponse();
    }

    private MockHttpServletResponse enAttente(Dossier d) throws Exception {
        return mvc.perform(get("/api/entreprises/" + d.id() + "/operations/propositions").header("Authorization", "Bearer " + jwtService.generateToken(d.expert()))).andReturn().getResponse();
    }

    private long propositions(Dossier d) {
        return jdbc.queryForObject("select count(*) from accounting_operation_proposal where entreprise_id = ?", Long.class, d.id());
    }

    @Test
    void propositionEnvoyeeDeuxFois_uneSeuleProposition_retrouveeDansLaListeEnAttente() throws Exception {
        Dossier d = nouveauDossier(false);
        String cle = "hl-" + UUID.randomUUID();
        String corps = corpsAchat(d, "Transport sacs de riz");

        MockHttpServletResponse premier = proposer(d, corps, cle);
        MockHttpServletResponse renvoi = proposer(d, corps, cle);

        assertThat(premier.getStatus()).isBetween(200, 299);
        assertThat(renvoi.getStatus()).isEqualTo(premier.getStatus());
        assertThat(renvoi.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(renvoi.getContentAsString()).isEqualTo(premier.getContentAsString());
        assertThat(propositions(d)).as("une seule proposition malgré deux envois").isEqualTo(1);

        MockHttpServletResponse liste = enAttente(d);
        assertThat(liste.getStatus()).isEqualTo(200);
        // le dossier de test n'a pas de règle de TVA : la proposition est « à revoir », elle figure quand même dans la liste des propositions en attente
        assertThat(liste.getContentAsString()).contains("Transport sacs de riz").containsAnyOf("\"statut\":\"PROPOSED\"", "\"statut\":\"NEEDS_REVIEW\"");
    }

    @Test
    void sansCle_chaqueEnvoiCreeUneProposition() throws Exception {
        Dossier d = nouveauDossier(false);
        proposer(d, corpsAchat(d, "A"), null);
        proposer(d, corpsAchat(d, "B"), null);
        assertThat(propositions(d)).isEqualTo(2);
    }

    @Test
    void laListeEnAttente_estPropreAuDossier_etRefuseeAuxAutres() throws Exception {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        proposer(a, corpsAchat(a, "Achat du dossier A"), null);

        assertThat(enAttente(b).getContentAsString()).doesNotContain("Achat du dossier A");
        // l'expert de B ne peut pas lister les propositions du dossier A
        var chez = mvc.perform(get("/api/entreprises/" + a.id() + "/operations/propositions").header("Authorization", "Bearer " + jwtService.generateToken(b.expert()))).andReturn().getResponse();
        assertThat(chez.getStatus()).isIn(403, 404);
    }

    @Test
    void uneProposition_rejetee_sortDeLaListeEnAttente() throws Exception {
        Dossier d = nouveauDossier(false);
        MockHttpServletResponse creee = proposer(d, corpsAchat(d, "Achat a ecarter"), null);
        String id = creee.getContentAsString().replaceAll(".*?\"id\":\"([0-9a-f-]{36})\".*", "$1");
        assertThat(enAttente(d).getContentAsString()).contains("Achat a ecarter");
        var rejetee = mvc.perform(post("/api/entreprises/" + d.id() + "/operations/propositions/" + id + "/rejeter").contentType(MediaType.APPLICATION_JSON)
                .content("{\"motif\":\"Doublon de saisie\"}").header("Authorization", "Bearer " + jwtService.generateToken(d.expert()))).andReturn().getResponse();
        assertThat(rejetee.getStatus()).isBetween(200, 299);
        assertThat(enAttente(d).getContentAsString()).doesNotContain("Achat a ecarter");
    }
}
