package com.example.comptable.document;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Une piece que la lecture automatique n'a pas pu lire (photo floue, service indisponible) ne bloque plus le comptable : le Hub dit
 * quoi faire, et il saisit lui-meme la date, le numero, le fournisseur et les montants. Chemin HTTP reel, vraie base.
 */
class DocumentCorrectionHttpTest extends CollecteTestBase {

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;
    @Autowired DocumentRepository documentRepository;

    private MockMvc mvc;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    private UUID pieceIllisible(Dossier d) {
        Document doc = new Document();
        doc.setTenant(entrepriseRepository.findById(d.id()).orElseThrow().getTenant());
        doc.setEntreprise(entrepriseRepository.getReferenceById(d.id()));
        doc.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        doc.setCanalSource(CanalSource.WEB);
        doc.setCheminFichier("test/floue.png");
        doc.setStatut(StatutDocument.ERREUR);
        return documentRepository.save(doc).getId();
    }

    private String jeton(Dossier d) {
        return "Bearer " + jwtService.generateToken(d.expert());
    }

    private MockHttpServletResponse corriger(Dossier d, UUID id, String json, String jeton) throws Exception {
        return mvc.perform(put("/api/entreprises/" + d.id() + "/documents/" + id + "/donnees").contentType(MediaType.APPLICATION_JSON).content(json)
                .header("Authorization", jeton)).andReturn().getResponse();
    }

    private String hub(Dossier d) throws Exception {
        return mvc.perform(get("/api/entreprises/" + d.id() + "/documents/hub").header("Authorization", jeton(d))).andReturn().getResponse().getContentAsString();
    }

    @Test
    void pieceIllisible_estSignaleeACompleter_puisLaSaisieLaRendExploitable() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = pieceIllisible(d);
        assertThat(hub(d)).contains("\"lectureIncomplete\":true").contains("n'a pas abouti");

        MockHttpServletResponse r = corriger(d, id, "{\"datePiece\":\"2026-11-10\",\"numeroFacture\":\"F-2026-77\",\"fournisseur\":\"Station XYZ\","
                + "\"montantHt\":100000,\"montantTtc\":118000}", jeton(d));
        assertThat(r.getStatus()).isEqualTo(200);

        Document apres = documentRepository.findById(id).orElseThrow();
        assertThat(apres.getStatut()).as("une piece en erreur redevient exploitable").isEqualTo(StatutDocument.TRAITE);
        assertThat(apres.getDatePiece()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(apres.getNumeroFacture()).isEqualTo("F-2026-77");
        assertThat(apres.getMontantTva()).as("la TVA se deduit de HT et TTC").isEqualByComparingTo(new BigDecimal("18000"));
        assertThat(apres.getTiersId()).as("le fournisseur saisi est identifie parmi les tiers existants").isEqualTo(d.fournisseur().id());
        assertThat(hub(d)).contains("\"lectureIncomplete\":false");
        assertThat(compter("select count(*) from audit_log where tenant_id = ? and action = 'DOCUMENT_DONNEES_CORRIGEES'", d.expert().getTenantId())).isEqualTo(1);
    }

    @Test
    void saisiesIncoherentesOuVides_sontRefusees_sansToucherLaPiece() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = pieceIllisible(d);
        assertThat(corriger(d, id, "{}", jeton(d)).getStatus()).as("rien a modifier").isEqualTo(422);
        assertThat(corriger(d, id, "{\"montantHt\":100000,\"montantTva\":18000,\"montantTtc\":150000}", jeton(d)).getStatus()).as("HT + TVA != TTC").isEqualTo(422);
        assertThat(corriger(d, id, "{\"montantHt\":-5}", jeton(d)).getStatus()).isEqualTo(422);
        assertThat(corriger(d, id, "{\"datePiece\":\"1850-01-01\"}", jeton(d)).getStatus()).as("date aberrante").isEqualTo(422);
        assertThat(documentRepository.findById(id).orElseThrow().getStatut()).isEqualTo(StatutDocument.ERREUR);
    }

    @Test
    void fournisseurChoisiDansLaListe_l_emporteSurLeNomLu_etUnTiersDUnAutreDossierEstRefuse() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = pieceIllisible(d);
        // un nom qui ne correspond a rien : seul l'identifiant choisi dans la liste peut designer le fournisseur
        MockHttpServletResponse r = corriger(d, id, "{\"fournisseur\":\"Nom tape de travers\",\"tiersId\":\"" + d.fournisseur().id() + "\"}", jeton(d));
        assertThat(r.getStatus()).isEqualTo(200);
        Document apres = documentRepository.findById(id).orElseThrow();
        assertThat(apres.getTiersId()).isEqualTo(d.fournisseur().id());
        assertThat(apres.getDonneesOcr().get("fournisseur")).as("le nom enregistre est celui du tiers choisi").isEqualTo("Station XYZ");

        Dossier autre = nouveauDossier(false);
        MockHttpServletResponse refus = corriger(d, id, "{\"tiersId\":\"" + autre.fournisseur().id() + "\"}", jeton(d));
        assertThat(refus.getStatus()).as("un tiers d'un autre dossier n'est jamais accepte").isEqualTo(422);
        assertThat(documentRepository.findById(id).orElseThrow().getTiersId()).isEqualTo(d.fournisseur().id());
    }

    @Test
    void isoleParCabinet() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID id = pieceIllisible(d);
        Dossier autre = nouveauDossier(false);
        assertThat(corriger(d, id, "{\"datePiece\":\"2026-11-10\"}", jeton(autre)).getStatus()).isEqualTo(404);
    }

    private long compter(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
