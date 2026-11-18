package com.example.comptable.comptabilite.reprise;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Reprise d'une balance d'ouverture : aperçu sans écriture, import en écriture d'à-nouveaux en brouillon, garde-fous. Chemin HTTP réel, vraie base. */
class RepriseBalanceHttpTest extends CollecteTestBase {

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;

    private MockMvc mvc;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    private MockHttpServletResponse envoyer(Dossier d, String chemin, String csv, String jeton) throws Exception {
        MockMultipartFile fichier = new MockMultipartFile("fichier", "balance.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return mvc.perform(multipart("/api/entreprises/" + d.id() + "/reprise-balance" + chemin).file(fichier).header("Authorization", jeton)).andReturn().getResponse();
    }

    private String jeton(Dossier d) {
        return "Bearer " + jwtService.generateToken(d.expert());
    }

    private static final String BALANCE_OK = """
            Compte;Libellé;Tiers;Débit;Crédit
            521;Banque Coris;;1 000 000;
            571;Caisse;;50 000;
            411;Créances clients;Client Alpha Reprise;300 000;
            401;Dettes fournisseurs;Station XYZ;;200 000
            101;Capital social;;;1 150 000
            """;

    private long compter(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void apercu_neEcritRien_expliqueChaqueLigne_etAnnonceLesTiersACreer() throws Exception {
        Dossier d = nouveauDossier(false);
        long ecrituresAvant = compter("select count(*) from ecriture_comptable where entreprise_id = ?", d.id());
        long tiersAvant = compter("select count(*) from tiers where entreprise_id = ?", d.id());

        MockHttpServletResponse r = envoyer(d, "/apercu", BALANCE_OK, jeton(d));
        assertThat(r.getStatus()).isEqualTo(200);
        String corps = r.getContentAsString();
        assertThat(corps).contains("\"importable\":true").contains("\"tiersACreer\":1").contains("\"erreurs\":0").contains("\"totalDebit\":1350000").contains("\"totalCredit\":1350000").contains("TIERS_A_CREER");
        assertThat(compter("select count(*) from ecriture_comptable where entreprise_id = ?", d.id())).as("un aperçu n'écrit jamais").isEqualTo(ecrituresAvant);
        assertThat(compter("select count(*) from tiers where entreprise_id = ?", d.id())).isEqualTo(tiersAvant);
    }

    @Test
    void import_creeUneEcritureDOuvertureEnBrouillon_creeLesTiers_etNePeutPasEtreRepete() throws Exception {
        Dossier d = nouveauDossier(false);
        long tiersAvant = compter("select count(*) from tiers where entreprise_id = ?", d.id());

        MockHttpServletResponse r = envoyer(d, "", BALANCE_OK, jeton(d));
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(r.getContentAsString()).contains("\"lignes\":5").contains("\"tiersCrees\":1");

        List<String> ecritures = jdbc.queryForList("select statut || '|' || nature || '|' || date_ecriture from ecriture_comptable where entreprise_id = ? and nature = 'OPENING'", String.class, d.id());
        assertThat(ecritures).hasSize(1);
        assertThat(ecritures.get(0)).startsWith("BROUILLON|OPENING|");
        assertThat(compter("select count(*) from ligne_ecriture l join ecriture_comptable e on e.id = l.ecriture_id where e.entreprise_id = ? and e.nature = 'OPENING'", d.id())).isEqualTo(5);
        assertThat(compter("select count(*) from tiers where entreprise_id = ?", d.id())).as("le client inconnu est créé, le fournisseur existant est réutilisé").isEqualTo(tiersAvant + 1);
        assertThat(compter("select count(*) from ligne_ecriture l join ecriture_comptable e on e.id = l.ecriture_id join tiers t on t.id = l.tiers_id where e.entreprise_id = ? and e.nature = 'OPENING'", d.id()))
                .as("les lignes clients et fournisseurs portent leur tiers").isEqualTo(2);
        assertThat(jdbc.queryForObject("select sum(debit) - sum(credit) from ligne_ecriture l join ecriture_comptable e on e.id = l.ecriture_id where e.entreprise_id = ? and e.nature = 'OPENING'", java.math.BigDecimal.class, d.id()))
                .isEqualByComparingTo("0");
        assertThat(compter("select count(*) from audit_log where tenant_id = ? and action = 'REPRISE_BALANCE_IMPORTEE'", d.expert().getTenantId())).isEqualTo(1);

        MockHttpServletResponse deuxieme = envoyer(d, "", BALANCE_OK, jeton(d));
        assertThat(deuxieme.getStatus()).as("une seule reprise par exercice").isEqualTo(422);
        assertThat(deuxieme.getContentAsString()).contains("existe déjà");
        assertThat(envoyer(d, "/apercu", BALANCE_OK, jeton(d)).getContentAsString()).contains("\"importable\":false").contains("existe déjà");
    }

    @Test
    void ecritureDOuverture_validee_entreDansLeBilan_maisPasDansLActiviteDeLaPeriode() throws Exception {
        Dossier d = nouveauDossier(false);
        String reponse = envoyer(d, "", BALANCE_OK, jeton(d)).getContentAsString();
        String ecritureId = reponse.replaceAll(".*\"ecriture\":\\{\"id\":\"([0-9a-f-]{36})\".*", "$1");
        MockHttpServletResponse validation = mvc.perform(post("/api/entreprises/" + d.id() + "/ecritures/" + ecritureId + "/valider").header("Authorization", jeton(d))).andReturn().getResponse();
        assertThat(validation.getStatus()).isEqualTo(200);
        assertThat(compter("select count(*) from ecriture_comptable where id = ?::uuid and statut = 'VALIDEE' and nature = 'OPENING'", ecritureId)).isEqualTo(1);
        // le solde de la banque reprise figure au grand livre légal (toutes natures)
        assertThat(jdbc.queryForObject("select sum(l.debit - l.credit) from ligne_ecriture l join ecriture_comptable e on e.id = l.ecriture_id join compte_comptable c on c.id = l.compte_id "
                + "where e.entreprise_id = ? and e.statut = 'VALIDEE' and c.numero = '521'", java.math.BigDecimal.class, d.id())).isEqualByComparingTo("1000000");
    }

    @Test
    void garde_fous_chaqueProblemeEstDitSurSaLigne_etBloqueLImport() throws Exception {
        Dossier d = nouveauDossier(false);
        String csv = """
                Compte;Libellé;Tiers;Débit;Crédit
                521;Banque;;1 000;
                7011;Ventes;;;500
                5999;Compte inexistant;;100;
                411;Clients sans nom;;100;
                571;Caisse;;-50;
                101;Capital;;;800
                """;
        MockHttpServletResponse r = envoyer(d, "/apercu", csv, jeton(d));
        assertThat(r.getStatus()).isEqualTo(200);
        String corps = r.getContentAsString();
        assertThat(corps).contains("\"importable\":false").contains("classes 1 à 5").contains("n'existe pas dans le plan comptable").contains("indiquez le nom du client").contains("négatif");
        assertThat(envoyer(d, "", csv, jeton(d)).getStatus()).isEqualTo(422);
        assertThat(compter("select count(*) from ecriture_comptable where entreprise_id = ? and nature = 'OPENING'", d.id())).isZero();
    }

    @Test
    void balanceNonEquilibree_estRefusee_sansCompteDAttenteInvente() throws Exception {
        Dossier d = nouveauDossier(false);
        String csv = "Compte;Débit;Crédit\n521;1000;\n101;;900\n";
        MockHttpServletResponse r = envoyer(d, "/apercu", csv, jeton(d));
        assertThat(r.getContentAsString()).contains("\"importable\":false").contains("\"ecart\":100").contains("pas équilibrée");
        assertThat(envoyer(d, "", csv, jeton(d)).getStatus()).isEqualTo(422);
    }

    @Test
    void reserveAuCabinet_etIsoleParCabinet() throws Exception {
        Dossier d = nouveauDossier(false);
        assertThat(mvc.perform(multipart("/api/entreprises/" + d.id() + "/reprise-balance/apercu").file(new MockMultipartFile("fichier", "b.csv", "text/csv", BALANCE_OK.getBytes(StandardCharsets.UTF_8)))).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        Dossier autre = nouveauDossier(false);
        assertThat(envoyer(d, "/apercu", BALANCE_OK, jeton(autre)).getStatus()).as("un autre cabinet ne voit pas ce dossier").isEqualTo(404);
    }
}
