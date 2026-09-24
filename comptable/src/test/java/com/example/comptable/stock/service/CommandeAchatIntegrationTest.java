package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.entreprise.service.EntrepriseService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.ModeGestionStock;
import com.example.comptable.stock.domain.PolitiqueStockNegatif;
import com.example.comptable.stock.domain.TypeArticle;
import com.example.comptable.stock.web.dto.ArticleDto;
import com.example.comptable.stock.web.dto.AvoirFournisseurDto;
import com.example.comptable.stock.web.dto.CommandeAchatDto;
import com.example.comptable.stock.web.dto.CreerArticleRequest;
import com.example.comptable.stock.web.dto.CreerAvoirFournisseurRequest;
import com.example.comptable.stock.web.dto.CreerCommandeAchatRequest;
import com.example.comptable.stock.web.dto.FactureFournisseurDto;
import com.example.comptable.stock.web.dto.LigneCommandeAchatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scenario complet sur PostgreSQL reel (meme convention que
 * {@link com.example.comptable.stock.inventaire.InventaireIntegrationTest}) : entreprise -&gt; PCE
 * -&gt; comptes d'achat -&gt; fournisseur -&gt; articles -&gt; commande multi-lignes multi-natures -&gt;
 * reception -&gt; facture fournisseur -&gt; verification comptes/TVA/total -&gt; rejeu (idempotence) -&gt;
 * avoir fournisseur -&gt; isolation multi-tenant (mission Etape 5, section 31).
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class CommandeAchatIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UtilisateurRepository utilisateurRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntrepriseService entrepriseService;
    @Autowired
    private TiersService tiersService;
    @Autowired
    private ArticleService articleService;
    @Autowired
    private CommandeAchatService commandeAchatService;
    @Autowired
    private AvoirFournisseurService avoirFournisseurService;
    @Autowired
    private CompteComptableRepository compteComptableRepository;

    @Test
    void scenario_complet_achat_multi_lignes_multi_natures_avec_avoir_et_isolation_multi_tenant() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entreprise = creerEntreprise(principal, "Achats Scenario Complet");

        CompteComptable compteStock = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "311").orElseThrow();
        CompteComptable compteAchatMarchandise = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "601").orElseThrow();
        CompteComptable compteAchatMatierePremiere = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "604").orElseThrow();
        CompteComptable compteVente = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "701").orElseThrow();
        CompteComptable compteVariation = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "6031").orElseThrow();

        TiersDto fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Cimenterie SARL", null, principal);

        // 2 articles de comptes d'achat distincts (601 vs 604) : la facture doit produire une
        // ligne de debit DISTINCTE par compte, jamais un seul 601 agrege (section 5 de la mission).
        ArticleDto marchandise = articleService.creer(entreprise.id(),
                new CreerArticleRequest("ART-CIMENT", "Sac de ciment 50kg", "Materiaux", TypeArticle.MARCHANDISE,
                        "unite", new BigDecimal("3000"), new BigDecimal("18"),
                        compteStock.getId(), compteAchatMarchandise.getId(), compteVente.getId(), compteVariation.getId(),
                        null, PolitiqueStockNegatif.INTERDIT, ModeGestionStock.PERMANENT),
                principal);
        ArticleDto matierePremiere = articleService.creer(entreprise.id(),
                new CreerArticleRequest("ART-SABLE", "Sable de construction", "Materiaux", TypeArticle.MATIERE_PREMIERE,
                        "sac", new BigDecimal("2000"), BigDecimal.ZERO,
                        compteStock.getId(), compteAchatMatierePremiere.getId(), compteVente.getId(), compteVariation.getId(),
                        null, PolitiqueStockNegatif.INTERDIT, ModeGestionStock.PERMANENT),
                principal);

        CommandeAchatDto commande = commandeAchatService.creer(entreprise.id(),
                new CreerCommandeAchatRequest(fournisseur.id(), LocalDate.now(), List.of(
                        new LigneCommandeAchatRequest(marchandise.id(), new BigDecimal("10"), new BigDecimal("2000")),
                        new LigneCommandeAchatRequest(matierePremiere.id(), new BigDecimal("5"), new BigDecimal("1000")))),
                principal);

        commandeAchatService.validerReception(entreprise.id(), commande.id(), principal);

        ArticleDto marchandiseApresReception = articleService.obtenir(entreprise.id(), marchandise.id(), principal);
        assertThat(marchandiseApresReception.quantiteEnStock()).isEqualByComparingTo("10");
        ArticleDto matierePremiereApresReception = articleService.obtenir(entreprise.id(), matierePremiere.id(), principal);
        assertThat(matierePremiereApresReception.quantiteEnStock()).isEqualByComparingTo("5");

        FactureFournisseurDto facture = commandeAchatService.genererFactureFournisseur(entreprise.id(), commande.id(), principal);

        // 10 x 2000 = 20000 HT (marchandise, TVA 18%) ; 5 x 1000 = 5000 HT (matiere premiere, sans TVA).
        // TVA = 18% de 20000 = 3600. TTC = 20000 + 5000 + 3600 = 28600.
        assertThat(facture.montantHt()).isEqualByComparingTo("25000");
        assertThat(facture.montantTva()).isEqualByComparingTo("3600");
        assertThat(facture.montantTtc()).isEqualByComparingTo("28600");
        assertThat(facture.ecritureId()).isNotNull();
        assertThat(facture.lignesEcriture()).hasSize(4);
        assertThat(facture.lignesEcriture()).anySatisfy(l -> {
            assertThat(l.compteNumero()).isEqualTo("601");
            assertThat(l.debit()).isEqualByComparingTo("20000");
        });
        assertThat(facture.lignesEcriture()).anySatisfy(l -> {
            assertThat(l.compteNumero()).isEqualTo("604");
            assertThat(l.debit()).isEqualByComparingTo("5000");
        });
        assertThat(facture.lignesEcriture()).anySatisfy(l -> {
            assertThat(l.compteNumero()).isEqualTo("4456");
            assertThat(l.debit()).isEqualByComparingTo("3600");
        });
        assertThat(facture.lignesEcriture()).anySatisfy(l -> assertThat(l.credit()).isEqualByComparingTo("28600"));
        BigDecimal totalDebit = facture.lignesEcriture().stream().map(l -> l.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = facture.lignesEcriture().stream().map(l -> l.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);

        // Rejeu : une commande deja facturee ne doit jamais produire une seconde ecriture.
        assertThatThrownBy(() -> commandeAchatService.genererFactureFournisseur(entreprise.id(), commande.id(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja ete facturee");

        // Avoir fournisseur total : inversion des comptes/montants, retour physique en stock.
        AvoirFournisseurDto avoir = avoirFournisseurService.creer(entreprise.id(), facture.id(),
                new CreerAvoirFournisseurRequest("Marchandise non conforme", true), principal);
        assertThat(avoir.montantHt()).isEqualByComparingTo("25000");
        assertThat(avoir.montantTtc()).isEqualByComparingTo("28600");
        assertThat(avoir.ecritureId()).isNotNull();
        assertThat(avoir.lignesEcriture()).anySatisfy(l -> {
            assertThat(l.compteNumero()).isEqualTo("601");
            assertThat(l.credit()).isEqualByComparingTo("20000");
        });
        assertThat(avoir.lignesEcriture()).anySatisfy(l -> assertThat(l.debit()).isEqualByComparingTo("28600"));

        ArticleDto marchandiseApresAvoir = articleService.obtenir(entreprise.id(), marchandise.id(), principal);
        assertThat(marchandiseApresAvoir.quantiteEnStock()).isEqualByComparingTo("0");
        ArticleDto matierePremiereApresAvoir = articleService.obtenir(entreprise.id(), matierePremiere.id(), principal);
        assertThat(matierePremiereApresAvoir.quantiteEnStock()).isEqualByComparingTo("0");

        // Un second avoir sur la meme facture est refuse.
        assertThatThrownBy(() -> avoirFournisseurService.creer(entreprise.id(), facture.id(),
                new CreerAvoirFournisseurRequest(null, false), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja");

        // Isolation multi-tenant.
        EntrepriseDto entrepriseB = creerEntreprise(principal, "Achats Isolation B");
        assertThatThrownBy(() -> articleService.obtenir(entrepriseB.id(), marchandise.id(), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThat(commandeAchatService.lister(entrepriseB.id(), principal)).isEmpty();
        assertThat(avoirFournisseurService.lister(entrepriseB.id(), principal)).isEmpty();
    }

    private EntrepriseDto creerEntreprise(UtilisateurPrincipal principal, String raisonSociale) {
        return entrepriseService.creer(
                new CreerEntrepriseRequest(raisonSociale, null, null, null, null, null, "CI", "XOF"),
                principal);
    }

    private UtilisateurPrincipal creerPrincipalExpertComptable() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Achats Test");
        tenant.setSlug("cabinet-achats-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("achats-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Achats");
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);

        return new UtilisateurPrincipal(utilisateur);
    }
}
