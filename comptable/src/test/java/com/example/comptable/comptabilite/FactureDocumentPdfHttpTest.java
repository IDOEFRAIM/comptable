package com.example.comptable.comptabilite;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.security.jwt.JwtService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Devis et factures en PDF : fiche légale de l'entreprise, mentions, montants, montant en lettres, brouillon, plusieurs pages. Chemin HTTP réel, vraie base, PDF réellement relu. */
class FactureDocumentPdfHttpTest extends CollecteTestBase {

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;

    private MockMvc mvc;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    private String jeton(Dossier d) {
        return "Bearer " + jwtService.generateToken(d.expert());
    }

    private MockHttpServletResponse remplirFiche(Dossier d) throws Exception {
        String corps = "{\"raisonSociale\":\"Boutique Sawadogo & Fils\",\"numeroContribuable\":\"00123456A\",\"rccm\":\"BF-OUA-2020-B-1234\",\"formeJuridique\":\"SARL\",\"regimeFiscal\":\"RSI\","
                + "\"adresse\":\"Avenue Kwame N'Krumah, Ouagadougou\",\"telephone\":\"+226 70 00 00 00\",\"email\":\"compta@sawadogo.test\","
                + "\"coordonneesBancaires\":\"Coris Bank BF - IBAN BF42 0000 1111\",\"mentionsFacture\":\"Paiement a 30 jours. Penalites de retard : 1,5 % par mois.\"}";
        return mvc.perform(put("/api/entreprises/" + d.id() + "/fiche").contentType(MediaType.APPLICATION_JSON).content(corps).header("Authorization", jeton(d))).andReturn().getResponse();
    }

    private String creerDocument(Dossier d, String type, int lignes) throws Exception {
        StringBuilder l = new StringBuilder();
        for (int i = 1; i <= lignes; i++) {
            l.append(i > 1 ? "," : "").append("{\"designation\":\"Prestation numéro ").append(i).append(" avec un libellé assez long pour devoir passer à la ligne dans le tableau du document\",\"quantite\":2,\"prixUnitaire\":50000,\"tauxTva\":18}");
        }
        if (lignes == 1) {
            l.setLength(0);
            l.append("{\"designation\":\"Mission de conseil\",\"quantite\":2,\"prixUnitaire\":50000,\"tauxTva\":18},{\"designation\":\"Déplacement\",\"quantite\":1,\"prixUnitaire\":10000,\"tauxTva\":0}");
        }
        String corps = "{\"tiersId\":\"" + d.fournisseur().id() + "\",\"dateEmission\":\"" + LocalDate.now() + "\",\"objet\":\"Accompagnement comptable\",\"lignes\":[" + l + "]}";
        MockHttpServletResponse r = mvc.perform(post("/api/entreprises/" + d.id() + "/factures").param("type", type).contentType(MediaType.APPLICATION_JSON).content(corps).header("Authorization", jeton(d)))
                .andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(r.getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private MockHttpServletResponse pdf(Dossier d, String id, String jeton) throws Exception {
        return mvc.perform(get("/api/entreprises/" + d.id() + "/factures/" + id + "/pdf").header("Authorization", jeton)).andReturn().getResponse();
    }

    private static String lire(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    void ficheLegale_seLitSeModifie_etListeCeQuiManque() throws Exception {
        Dossier d = nouveauDossier(false);
        String avant = mvc.perform(get("/api/entreprises/" + d.id() + "/fiche").header("Authorization", jeton(d))).andReturn().getResponse().getContentAsString();
        assertThat(avant).contains("\"manquants\"").contains("Adresse");

        assertThat(remplirFiche(d).getStatus()).isEqualTo(200);
        String apres = mvc.perform(get("/api/entreprises/" + d.id() + "/fiche").header("Authorization", jeton(d))).andReturn().getResponse().getContentAsString();
        assertThat(apres).contains("00123456A").contains("BF-OUA-2020-B-1234").contains("\"manquants\":[]");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entite_id = ? and action = 'FICHE_ENTREPRISE_MODIFIEE'", Long.class, d.id().toString())).isEqualTo(1);
        assertThat(mvc.perform(put("/api/entreprises/" + d.id() + "/fiche").contentType(MediaType.APPLICATION_JSON).content("{\"raisonSociale\":\"\"}").header("Authorization", jeton(d))).andReturn().getResponse().getStatus())
                .as("le nom est obligatoire").isEqualTo(400);
    }

    @Test
    void devisEnPdf_porteLesMentionsLegales_lesMontants_etLeMontantEnLettres() throws Exception {
        Dossier d = nouveauDossier(false);
        remplirFiche(d);
        String id = creerDocument(d, "DEVIS", 1);

        MockHttpServletResponse r = pdf(d, id, jeton(d));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentType()).isEqualTo("application/pdf");
        assertThat(r.getHeader("Content-Disposition")).contains("DEV-").contains(".pdf");
        String texte = lire(r.getContentAsByteArray());
        assertThat(texte).contains("DEVIS").contains("Boutique Sawadogo & Fils").contains("IFU : 00123456A").contains("RCCM : BF-OUA-2020-B-1234").contains("SARL")
                .contains("Avenue Kwame N'Krumah").contains("compta@sawadogo.test");
        assertThat(texte).contains("Station XYZ").contains("Mission de conseil").contains("Déplacement").contains("Accompagnement comptable");
        // 2 x 50 000 + 10 000 = 110 000 HT ; TVA 18 % sur 100 000 = 18 000 ; TTC 128 000
        assertThat(texte).contains("110 000").contains("18 000").contains("128 000 FCFA");
        assertThat(texte).contains("Cent vingt-huit mille francs CFA TTC");
        assertThat(texte).contains("Coris Bank BF").contains("Penalites de retard").contains("Bon pour accord");
        assertThat(texte).as("un devis neuf est un brouillon : jamais présenté comme un document valable").contains("BROUILLON");
        assertThat(texte).contains("Page 1 / 1");
    }

    @Test
    void sansFicheRemplie_lesMentionsAbsentesSontDitesAbsentes_jamaisInventees() throws Exception {
        Dossier d = nouveauDossier(false);
        String id = creerDocument(d, "FACTURE", 1);
        String texte = lire(pdf(d, id, jeton(d)).getContentAsByteArray());
        assertThat(texte).contains("FACTURE").contains("IFU : non renseigné").contains("RCCM : non renseigné").contains("Arrêtée la présente facture");
    }

    @Test
    void unDocumentLong_passeSurPlusieursPages_avecEnTeteDeTableauEtTotauxIntacts() throws Exception {
        Dossier d = nouveauDossier(false);
        remplirFiche(d);
        String id = creerDocument(d, "FACTURE", 45);
        byte[] contenu = pdf(d, id, jeton(d)).getContentAsByteArray();
        assertThat(pages(contenu)).isGreaterThanOrEqualTo(2);
        String texte = lire(contenu);
        assertThat(texte).contains("Prestation numéro 1 ").contains("Prestation numéro 45 ").contains("Page 1 / ").contains("Page 2 / ");
        // 45 lignes de 2 x 50 000 = 4 500 000 HT, TVA 810 000, TTC 5 310 000
        assertThat(texte).contains("4 500 000").contains("810 000").contains("5 310 000 FCFA").contains("Cinq millions trois cent dix mille francs CFA TTC");
    }

    @Test
    void lePdfEstIsoleParCabinet_etRefuseSansConnexion() throws Exception {
        Dossier d = nouveauDossier(false);
        String id = creerDocument(d, "DEVIS", 1);
        assertThat(mvc.perform(get("/api/entreprises/" + d.id() + "/factures/" + id + "/pdf")).andReturn().getResponse().getStatus()).isEqualTo(401);
        Dossier autre = nouveauDossier(false);
        assertThat(pdf(d, id, jeton(autre)).getStatus()).as("un autre cabinet ne lit pas ce document").isEqualTo(404);
        assertThat(pdf(autre, id, jeton(autre)).getStatus()).as("même cabinet, mauvais dossier").isEqualTo(404);
    }
}
