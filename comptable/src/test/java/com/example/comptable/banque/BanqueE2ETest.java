package com.example.comptable.banque;

import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.security.jwt.JwtService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Parcours Banque complet par l'API, sur PostgreSQL : apercu et import d'un releve, synthese, exact automatique, probable confirme, ambigu
 * tranche par le comptable parmi des candidats, sans correspondance, paiement partiel puis solde, annulation, reimport, isolation, securite.
 */
class BanqueE2ETest extends BankTestBase {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void mock() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    private String jeton(UtilisateurPrincipal p) {
        return jwtService.generateToken(p);
    }

    private UtilisateurPrincipal client(Dossier d) {
        Utilisateur u = utilisateurRepository.findAll().stream().filter(x -> d.telephone() != null && d.telephone().equals(x.getTelephone()))
                .findFirst().orElseThrow();
        return new UtilisateurPrincipal(u);
    }

    /** Premier element d'un chemin JSONPath de filtre (qui renvoie toujours une liste). */
    private static Object premier(String json, String chemin) {
        List<?> valeurs = JsonPath.read(json, chemin);
        assertThat(valeurs).as(chemin).isNotEmpty();
        return valeurs.get(0);
    }

    private MockHttpServletResponse envoyer(AbstractMockHttpServletRequestBuilder<?> requete, Dossier d) throws Exception {
        return mockMvc.perform(requete.header("Authorization", "Bearer " + jeton(d.expert()))).andReturn().getResponse();
    }

    private String corps(AbstractMockHttpServletRequestBuilder<?> requete, Dossier d, int statutAttendu) throws Exception {
        MockHttpServletResponse r = envoyer(requete, d);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(statutAttendu);
        return r.getContentAsString();
    }

    private AbstractMockHttpServletRequestBuilder<?> json(AbstractMockHttpServletRequestBuilder<?> b, String corps) {
        return b.contentType(MediaType.APPLICATION_JSON).content(corps);
    }

    @Test
    void parcoursComplet_dImportALAllocation() throws Exception {
        Dossier a = nouveauDossier(true);
        String base = "/api/entreprises/" + a.id() + "/banque";
        LocalDate aujourdhui = LocalDate.now();

        // Comptabilite : 1 reglement certain, 1 probable (date eloignee), 2 identiques (ambigu), et des factures a regler plus tard.
        Reglement exact = reglement(a, 1000, aujourdhui, "Reglement Station XYZ");
        Reglement probable = reglement(a, 2000, aujourdhui, "Reglement Station XYZ acompte");
        Reglement ambigu1 = reglement(a, 3000, aujourdhui, "Reglement lot A");
        Reglement ambigu2 = reglement(a, 3000, aujourdhui, "Reglement lot B");
        UUID facture = factureAchat(a, 7000);
        Reglement acompte = reglement(a, 2500, aujourdhui, "Acompte facture");
        Reglement solde = reglement(a, 4500, aujourdhui, "Solde facture");

        String csv = "date;libelle;montant\n"
                + aujourdhui + ";VIR STATION XYZ;-1000\n"
                + aujourdhui.plusDays(10) + ";VIR STATION XYZ ACOMPTE;-2000\n"
                + aujourdhui + ";VIR DIVERS;-3000\n"
                + aujourdhui + ";PAIEMENT MYSTERE;-777000\n"
                + aujourdhui + ";FRAIS TENUE DE COMPTE;-1500\n";
        MockMultipartFile fichier = new MockMultipartFile("fichier", "releve.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 1. Apercu : rien n'est ecrit.
        String apercu = corps(multipart(base + "/imports/apercu").file(fichier).param("compteId", exact.compteBanqueId().toString()), a, 200);
        assertThat((Integer) JsonPath.read(apercu, "$.lignesTotal")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(apercu, "$.aImporter")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(apercu, "$.doublonsExacts")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ligne_releve where entreprise_id = ?", Long.class, a.id())).isZero();

        // 2. Import (+ rapprochement automatique des cas certains).
        String import1 = corps(multipart(base + "/imports").file(fichier).param("compteId", exact.compteBanqueId().toString()), a, 201);
        assertThat((Integer) JsonPath.read(import1, "$.lot.importedRows")).isEqualTo(5);
        assertThat((List<?>) JsonPath.read(import1, "$.releve.lignes")).hasSize(5);

        // 3. Synthese : 1 rapprochee, 1 a confirmer, 1 ambigue, 2 sans correspondance.
        String synthese = corps(get(base + "/synthese").param("compteId", exact.compteBanqueId().toString()), a, 200);
        assertThat((Integer) JsonPath.read(synthese, "$.transactions")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(synthese, "$.rapprochees")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(synthese, "$.aConfirmer")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(synthese, "$.ambigues")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(synthese, "$.sansCorrespondance")).isEqualTo(2);
        assertThat((Double) JsonPath.read(synthese, "$.tauxRapproche")).isEqualTo(20.0);

        // 4. Le probable est propose avec sa confiance et son explication ; le comptable confirme en un clic.
        String aConfirmer = corps(get(base + "/transactions").param("statut", "A_CONFIRMER"), a, 200);
        assertThat((Integer) JsonPath.read(aConfirmer, "$.total")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(aConfirmer, "$.lignes[0].proposition.confiance")).isGreaterThanOrEqualTo(55);
        assertThat((String) JsonPath.read(aConfirmer, "$.lignes[0].proposition.explication")).contains("Montant exact");
        String reconciliationId = JsonPath.read(aConfirmer, "$.lignes[0].proposition.reconciliationId");
        corps(post(base + "/rapprochements/" + reconciliationId + "/confirmer"), a, 200);
        corps(post(base + "/rapprochements/" + reconciliationId + "/confirmer"), a, 200); // double clic
        assertThat((Integer) JsonPath.read(corps(get(base + "/synthese"), a, 200), "$.rapprochees")).isEqualTo(2);

        // 5. L'ambigu : jamais tranche par le systeme ; le comptable choisit parmi les candidats.
        String ambigus = corps(get(base + "/transactions").param("statut", "AMBIGUE"), a, 200);
        assertThat((Integer) JsonPath.read(ambigus, "$.total")).isEqualTo(1);
        String txAmbigue = JsonPath.read(ambigus, "$.lignes[0].id");
        String candidats = corps(get(base + "/transactions/" + txAmbigue + "/candidats").param("q", "lot"), a, 200);
        assertThat((List<?>) JsonPath.read(candidats, "$")).hasSize(2);
        assertThat((Integer) JsonPath.read(candidats, "$[0].score")).isGreaterThanOrEqualTo(50);
        corps(json(post(base + "/transactions/" + txAmbigue + "/rapprocher"), "{\"ligneEcritureId\":\"" + ambigu1.ligneBanqueId() + "\"}"), a, 200);
        assertThat((Integer) JsonPath.read(corps(get(base + "/synthese"), a, 200), "$.ambigues")).isZero();

        // 6. Sans correspondance : 2 exceptions, rien d'invente (pas d'ecriture creee depuis la banque).
        String sans = corps(get(base + "/transactions").param("statut", "SANS_CORRESPONDANCE"), a, 200);
        assertThat((Integer) JsonPath.read(sans, "$.total")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ?", Long.class, a.id())).isEqualTo(7);

        // 7. Annulation d'un rapprochement, motif obligatoire ; l'etat est restaure et la decision humaine respectee.
        String txExact = JsonPath.read(corps(get(base + "/transactions").param("statut", "RAPPROCHEE").param("q", "STATION XYZ"), a, 200), "$.lignes[0].id");
        assertThat(envoyer(json(post(base + "/transactions/" + txExact + "/annuler-rapprochement"), "{\"motif\":\"\"}"), a).getStatus()).isEqualTo(422);
        corps(json(post(base + "/transactions/" + txExact + "/annuler-rapprochement"), "{\"motif\":\"Mauvaise ecriture\"}"), a, 200);
        controlRunService.executer(entrepriseRepository.findById(a.id()).orElseThrow());
        assertThat((Integer) JsonPath.read(corps(get(base + "/synthese"), a, 200), "$.rapprochees")).isEqualTo(2);

        // 8. Reglements -> facture : paiement partiel, puis solde.
        String aRepartir = corps(get(base + "/paiements/reglements-a-repartir"), a, 200);
        assertThat((List<?>) JsonPath.read(aRepartir, "$[?(@.montant == 2500)].piece")).hasSize(1);
        String candidatesFactures = corps(get(base + "/paiements/reglements/" + acompte.ligneTiersId() + "/factures-candidates"), a, 200);
        assertThat((List<?>) JsonPath.read(candidatesFactures, "$[?(@.invoiceLineId == '" + facture + "')]")).hasSize(1);

        String allocation1 = corps(json(post(base + "/paiements/allocations"),
                "{\"paymentLineId\":\"" + acompte.ligneTiersId() + "\",\"invoiceLineId\":\"" + facture + "\",\"montant\":2500}"), a, 201);
        String factures = corps(get(base + "/paiements/factures"), a, 200);
        assertThat(premier(factures, "$[?(@.invoiceLineId == '" + facture + "')].statut")).isEqualTo("PARTIELLEMENT_PAYEE");
        assertThat(((Number) premier(factures, "$[?(@.invoiceLineId == '" + facture + "')].reste")).intValue()).isEqualTo(4500);
        // Un montant superieur au reste est refuse.
        assertThat(envoyer(json(post(base + "/paiements/allocations"),
                "{\"paymentLineId\":\"" + solde.ligneTiersId() + "\",\"invoiceLineId\":\"" + facture + "\",\"montant\":5000}"), a).getStatus()).isEqualTo(422);
        corps(json(post(base + "/paiements/allocations"),
                "{\"paymentLineId\":\"" + solde.ligneTiersId() + "\",\"invoiceLineId\":\"" + facture + "\",\"montant\":4500}"), a, 201);
        assertThat(((List<?>) JsonPath.read(corps(get(base + "/paiements/factures"), a, 200), "$[?(@.invoiceLineId == '" + facture + "')]"))).isEmpty(); // soldee

        String allocationId = JsonPath.read(allocation1, "$.id");
        corps(json(post(base + "/paiements/allocations/" + allocationId + "/annuler"), "{\"motif\":\"Mauvais reglement\"}"), a, 200);
        assertThat(premier(corps(get(base + "/paiements/factures"), a, 200), "$[?(@.invoiceLineId == '" + facture + "')].statut"))
                .isEqualTo("PARTIELLEMENT_PAYEE");

        // 9. Reimport du meme fichier : aucun doublon, lot trace.
        String reimport = corps(multipart(base + "/imports").file(fichier).param("compteId", exact.compteBanqueId().toString()), a, 200);
        assertThat((String) JsonPath.read(reimport, "$.lot.status")).isEqualTo("NOTHING_NEW");
        assertThat((Integer) JsonPath.read(reimport, "$.lot.exactDuplicates")).isEqualTo(5);
        assertThat(JsonPath.<Object>read(reimport, "$.releve")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from ligne_releve where entreprise_id = ?", Long.class, a.id())).isEqualTo(5);
        assertThat((List<?>) JsonPath.read(corps(get(base + "/imports"), a, 200), "$")).hasSize(2);

        // 10. Mesures.
        String metriques = corps(get(base + "/metriques"), a, 200);
        assertThat((Integer) JsonPath.read(metriques, "$.transactions")).isEqualTo(5);
        assertThat(((Number) JsonPath.read(metriques, "$.montantNonRapproche")).intValue()).isGreaterThan(700000);

        // 11. Isolation : l'entreprise B ne voit rien et ne peut rien faire sur les objets de A.
        Dossier b = nouveauDossier(false);
        String baseB = "/api/entreprises/" + b.id() + "/banque";
        assertThat((Integer) JsonPath.read(corps(get(baseB + "/transactions"), b, 200), "$.total")).isZero();
        assertThat(envoyer(json(post(baseB + "/transactions/" + txAmbigue + "/rapprocher"), "{\"ligneEcritureId\":\"" + ambigu2.ligneBanqueId() + "\"}"), b)
                .getStatus()).isEqualTo(404);
        assertThat(envoyer(get(baseB + "/transactions/" + txAmbigue + "/candidats"), b).getStatus()).isEqualTo(404);
        assertThat(envoyer(json(post(baseB + "/paiements/allocations"),
                "{\"paymentLineId\":\"" + probable.ligneTiersId() + "\",\"invoiceLineId\":\"" + facture + "\",\"montant\":10}"), b).getStatus()).isEqualTo(404);
        assertThat(envoyer(get(baseB + "/imports"), b).getContentAsString()).isEqualTo("[]");
    }

    @Test
    void securite_clientRefuse_anonymeRefuse_cabinetJamaisBloque() throws Exception {
        Dossier d = nouveauDossier(true);
        String base = "/api/entreprises/" + d.id() + "/banque";
        UUID id = UUID.randomUUID();
        List<String[]> routes = List.of(new String[] {"GET", "/synthese"}, new String[] {"GET", "/metriques"}, new String[] {"GET", "/transactions"},
                new String[] {"GET", "/transactions/" + id + "/candidats"}, new String[] {"POST", "/transactions/" + id + "/rapprocher"},
                new String[] {"POST", "/transactions/" + id + "/annuler-rapprochement"}, new String[] {"POST", "/rapprochements/" + id + "/confirmer"},
                new String[] {"GET", "/rapprochements/lot/apercu"}, new String[] {"POST", "/rapprochements/lot/confirmer"},
                new String[] {"GET", "/imports"}, new String[] {"POST", "/imports"}, new String[] {"POST", "/imports/apercu"},
                new String[] {"GET", "/paiements/reglements-a-repartir"}, new String[] {"GET", "/paiements/factures"},
                new String[] {"POST", "/paiements/allocations"}, new String[] {"POST", "/paiements/allocations/" + id + "/annuler"});
        for (String[] r : routes) {
            assertThat(statut(r, base, jeton(client(d)))).as("CLIENT " + r[0] + " " + r[1]).isEqualTo(403);
            assertThat(statut(r, base, null)).as("anonyme " + r[0] + " " + r[1]).isIn(401, 403);
            assertThat(statut(r, base, jeton(d.expert()))).as("cabinet " + r[0] + " " + r[1]).isNotIn(401, 403);
        }
    }

    private int statut(String[] route, String base, String bearer) throws Exception {
        AbstractMockHttpServletRequestBuilder<?> b = "GET".equals(route[0]) ? get(base + route[1]) : post(base + route[1]);
        b = b.contentType(MediaType.APPLICATION_JSON).content("{}");
        if (bearer != null) {
            b = b.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(b).andReturn().getResponse().getStatus();
    }
}
