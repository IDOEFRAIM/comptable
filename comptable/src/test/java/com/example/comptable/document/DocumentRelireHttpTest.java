package com.example.comptable.document;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.ocr.OcrExtractionService;
import com.example.comptable.document.ocr.OcrResult;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.storage.DocumentStorageService;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * « Relire avec l'IA » : le serveur relit le fichier deja stocke, sans que la piece soit renvoyee. Une relecture qui echoue ne doit
 * JAMAIS effacer ce qui etait lu ou saisi. L'extraction est simulee (jamais un vrai appel Groq). Chemin HTTP reel, vraie base.
 */
class DocumentRelireHttpTest extends CollecteTestBase {

    @MockitoBean OcrExtractionService ocr;
    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentStorageService stockage;

    private MockMvc mvc;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        when(ocr.nomMoteur()).thenReturn("test");
    }

    private UUID pieceEnErreur(Dossier d) throws Exception {
        var entreprise = entrepriseRepository.findById(d.id()).orElseThrow();
        var fichier = stockage.stocker(entreprise.getTenant().getId(), d.id(), "floue.png", new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
        Document doc = new Document();
        doc.setTenant(entreprise.getTenant());
        doc.setEntreprise(entreprise);
        doc.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        doc.setCanalSource(CanalSource.WEB);
        doc.setNomFichierOriginal("floue.png");
        doc.setContentType("image/png");
        doc.setCheminFichier(fichier.cheminFichier());
        doc.setStatut(StatutDocument.ERREUR);
        return documentRepository.save(doc).getId();
    }

    private String jeton(Dossier d) {
        return "Bearer " + jwtService.generateToken(d.expert());
    }

    private MockHttpServletResponse relire(Dossier d, UUID id, String jeton) throws Exception {
        return mvc.perform(post("/api/entreprises/" + d.id() + "/documents/" + id + "/relire").header("Authorization", jeton)).andReturn().getResponse();
    }

    @Test
    void relectureReussie_remplitLaPiece_sansLaRenvoyer() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = pieceEnErreur(d);
        when(ocr.extraire(any(), any())).thenReturn(new OcrResult(new BigDecimal("100000"), new BigDecimal("18000"), new BigDecimal("118000"),
                LocalDate.of(2026, 11, 10), true, "Lue", 0.9, "Station XYZ"));

        assertThat(relire(d, id, jeton(d)).getStatus()).isEqualTo(200);

        Document apres = documentRepository.findById(id).orElseThrow();
        // plus en erreur ; le controle de fraude habituel peut en plus l'orienter vers une validation humaine (faux fichier de test)
        assertThat(apres.getStatut()).isIn(StatutDocument.TRAITE, StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE);
        assertThat(apres.getMontantTtc()).isEqualByComparingTo("118000");
        assertThat(apres.getDatePiece()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(apres.getTiersId()).as("le fournisseur lu est identifie parmi les tiers").isEqualTo(d.fournisseur().id());
    }

    @Test
    void relectureQuiEchoue_neTouchePasLaPiece_etDitQuoiFaire() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = pieceEnErreur(d);
        Document avant = documentRepository.findById(id).orElseThrow();
        avant.setMontantTtc(new BigDecimal("5000")); // ce qui avait deja pu etre lu
        documentRepository.save(avant);
        when(ocr.extraire(any(), any())).thenReturn(OcrResult.echec("quota atteint"));

        MockHttpServletResponse r = relire(d, id, jeton(d));
        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(r.getContentAsString()).contains("toujours pas abouti").contains("completez la piece a la main");
        Document apres = documentRepository.findById(id).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutDocument.ERREUR);
        assertThat(apres.getMontantTtc()).as("rien de ce qui etait lu n'est efface").isEqualByComparingTo("5000");
    }

    @Test
    void pieceCompleteeALaMain_neSeRelitPas_carLaRelectureEffacerait_laSaisie() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = pieceEnErreur(d);
        assertThat(mvc.perform(put("/api/entreprises/" + d.id() + "/documents/" + id + "/donnees").contentType(MediaType.APPLICATION_JSON)
                .content("{\"datePiece\":\"2026-11-10\",\"montantHt\":1000,\"montantTtc\":1180}").header("Authorization", jeton(d))).andReturn().getResponse().getStatus()).isEqualTo(200);
        MockHttpServletResponse r = relire(d, id, jeton(d));
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(r.getContentAsString()).contains("completee a la main");
    }

    @Test
    void tuileACompleter_listeLesPiecesIllisibles_etIsoleParCabinet() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = pieceEnErreur(d);
        String liste = mvc.perform(get("/api/entreprises/" + d.id() + "/documents/hub").param("tuile", "A_COMPLETER").header("Authorization", jeton(d)))
                .andReturn().getResponse().getContentAsString();
        assertThat(liste).contains(id.toString()).contains("\"lectureIncomplete\":true");
        Dossier autre = nouveauDossier(false);
        assertThat(relire(d, id, jeton(autre)).getStatus()).isEqualTo(404);
    }
}
