package com.example.comptable.investigation;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Le workflow de cloture vu de l'API HTTP reelle (corps JSON, statuts, messages). Ce test existe parce que le navigateur a revele un 400 sur le corps vide
 * {@code {}} envoye par l'ecran : un corps facultatif ne doit JAMAIS empecher la cloture d'un dossier propre.
 */
class ClotureHttpTest extends BankTestBase {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtService jwtService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired org.springframework.core.env.Environment environment;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    private MockHttpServletRequestBuilder cloturer(Dossier d, String corps) {
        UUID exercice = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
        MockHttpServletRequestBuilder requete = post("/api/entreprises/" + d.id() + "/exercices/" + exercice + "/cloturer")
                .header("Authorization", "Bearer " + jwtService.generateToken(d.expert()));
        return corps == null ? requete : requete.contentType(MediaType.APPLICATION_JSON).content(corps);
    }

    /** Sans cette propriete (Spring Boot 4), les messages metier des refus (liste des bloquants, avertissements a confirmer) n'atteignent jamais l'ecran. */
    @Test
    void lesMessagesDesRefusSontExposesALInterface() {
        assertThat(environment.getProperty("spring.web.error.include-message")).isEqualTo("always");
    }

    @Test
    void corpsVideOuAbsent_nEmpechePasLaClotureD_unDossierPropre() throws Exception {
        Dossier propre = nouveauDossier(false);
        assertThat(mockMvc.perform(cloturer(propre, "{}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        Dossier autre = nouveauDossier(false);
        assertThat(mockMvc.perform(cloturer(autre, null)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void avertissements_409AvecLaListe_puisConfirmationMotivee() throws Exception {
        Dossier d = nouveauDossier(false);
        acheterSansPiece(d, 90_000);

        var sansConfirmation = mockMvc.perform(cloturer(d, "{}")).andReturn().getResponse();
        assertThat(sansConfirmation.getStatus()).isEqualTo(409);
        assertThat(sansConfirmation.getContentAsString()).contains("AVERTISSEMENTS_A_CONFIRMER");

        assertThat(mockMvc.perform(cloturer(d, "{\"avertissementsConfirmes\":true}")).andReturn().getResponse().getStatus()).isEqualTo(422);
        assertThat(mockMvc.perform(cloturer(d, "{\"avertissementsConfirmes\":true,\"motif\":\"Pieces attendues en janvier\"}")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void blocage_422AvecLaListeDesBloquants() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID modele = reglement(d, 1_000, LocalDate.now(), "Modele").ecritureId();
        UUID brouillon = UUID.randomUUID();
        jdbc.update("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut) select ?, entreprise_id, exercice_id, journal_id, "
                + "'BRO-HTTP', date_ecriture, 'Brouillon', 'BROUILLON' from ecriture_comptable where id = ?", brouillon, modele);

        var reponse = mockMvc.perform(cloturer(d, "{\"avertissementsConfirmes\":true,\"motif\":\"x\"}")).andReturn().getResponse();

        assertThat(reponse.getStatus()).isEqualTo(422);
        assertThat(reponse.getContentAsString()).containsIgnoringCase("brouillon");
    }
}
