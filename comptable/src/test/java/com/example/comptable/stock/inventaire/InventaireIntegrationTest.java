package com.example.comptable.stock.inventaire;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.comptabilite.service.TiersService;
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
import com.example.comptable.stock.inventaire.domain.StatutInventaire;
import com.example.comptable.stock.inventaire.service.InventaireService;
import com.example.comptable.stock.inventaire.web.dto.CreerInventaireRequest;
import com.example.comptable.stock.inventaire.web.dto.InventaireDto;
import com.example.comptable.stock.inventaire.web.dto.SaisirLigneInventaireRequest;
import com.example.comptable.stock.service.ArticleService;
import com.example.comptable.stock.service.CommandeAchatService;
import com.example.comptable.stock.web.dto.ArticleDto;
import com.example.comptable.stock.web.dto.CommandeAchatDto;
import com.example.comptable.stock.web.dto.CreerArticleRequest;
import com.example.comptable.stock.web.dto.CreerCommandeAchatRequest;
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
 * {@link com.example.comptable.comptabilite.PlanComptableIntegrationTest}/
 * {@link com.example.comptable.comptabilite.operation.AccountingOperationEngineIntegrationTest}) :
 * entreprise -&gt; PCE -&gt; article -&gt; fournisseur -&gt; achat -&gt; stock -&gt; vente -&gt; stock ->
 * inventaire -&gt; ecart -&gt; proposition -&gt; validation -&gt; ecriture, plus isolation multi-tenant.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class InventaireIntegrationTest {

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
    private FacturationService facturationService;
    @Autowired
    private InventaireService inventaireService;
    @Autowired
    private AccountingOperationEngine accountingOperationEngine;
    @Autowired
    private CompteComptableRepository compteComptableRepository;

    @Test
    void scenario_complet_achat_vente_inventaire_avec_regularisation_comptabilisee() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entreprise = creerEntreprise(principal, "Stock Scenario Complet");

        CompteComptable compteStock = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "311").orElseThrow();
        CompteComptable compteAchat = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "601").orElseThrow();
        CompteComptable compteVente = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "701").orElseThrow();
        CompteComptable compteVariation = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "6031").orElseThrow();

        // 3-4. Article configure avec ses 4 comptes explicites (jamais devines).
        ArticleDto article = articleService.creer(entreprise.id(),
                new CreerArticleRequest("ART-CIMENT", "Sac de ciment 50kg", "Materiaux", TypeArticle.MARCHANDISE,
                        "unite", new BigDecimal("3000"), new BigDecimal("18"),
                        compteStock.getId(), compteAchat.getId(), compteVente.getId(), compteVariation.getId(),
                        null, PolitiqueStockNegatif.INTERDIT, ModeGestionStock.PERMANENT),
                principal);

        // 5. Fournisseur.
        TiersDto fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Cimenterie SARL", null, principal);

        // 6. Achat de 10 unites a 2000 HT -> reception declenche l'entree en stock.
        CommandeAchatDto commande = commandeAchatService.creer(entreprise.id(),
                new CreerCommandeAchatRequest(fournisseur.id(), LocalDate.now(),
                        List.of(new LigneCommandeAchatRequest(article.id(), new BigDecimal("10"), new BigDecimal("2000")))),
                principal);
        commandeAchatService.validerReception(entreprise.id(), commande.id(), principal);

        // 7. Stock verifie : 10 unites au PUMP 2000.
        ArticleDto apresAchat = articleService.obtenir(entreprise.id(), article.id(), principal);
        assertThat(apresAchat.quantiteEnStock()).isEqualByComparingTo("10");
        assertThat(apresAchat.pump()).isEqualByComparingTo("2000");

        // 8. Vente de 3 unites.
        TiersDto client = tiersService.creer(entreprise.id(), TypeTiers.CLIENT, "Client Chantier SARL", null, principal);
        FactureDto facture = facturationService.creer(entreprise.id(), TypeFacture.FACTURE,
                new CreerFactureRequest(client.id(), LocalDate.now(), null, "Vente ciment",
                        List.of(new LigneFactureRequest("Sac de ciment", new BigDecimal("3"), new BigDecimal("3500"),
                                new BigDecimal("18"), article.id()))),
                principal);
        facturationService.validerFacture(entreprise.id(), facture.id(), compteVente.getId(), principal);

        // 9. Stock verifie : 7 unites restantes, PUMP inchange par une sortie.
        ArticleDto apresVente = articleService.obtenir(entreprise.id(), article.id(), principal);
        assertThat(apresVente.quantiteEnStock()).isEqualByComparingTo("7");
        assertThat(apresVente.pump()).isEqualByComparingTo("2000");

        // 10-11. Inventaire : comptage physique de 6 unites (perte constatee de 1 unite).
        InventaireDto inventaire = inventaireService.creer(entreprise.id(),
                new CreerInventaireRequest(LocalDate.now(), "Inventaire de fin de mois"), principal);
        inventaire = inventaireService.saisirLigne(entreprise.id(), inventaire.id(),
                new SaisirLigneInventaireRequest(article.id(), new BigDecimal("6"), null), principal);

        // 12. Ecart calcule automatiquement.
        assertThat(inventaire.lignes()).hasSize(1);
        assertThat(inventaire.lignes().get(0).ecart()).isEqualByComparingTo("-1");
        assertThat(inventaire.lignes().get(0).valeurEcart()).isEqualByComparingTo("2000");

        // 13-14. Validation : mouvement de regularisation enregistre + proposition comptable generee.
        InventaireDto valide = inventaireService.valider(entreprise.id(), inventaire.id(), principal);
        assertThat(valide.statut()).isEqualTo("VALIDE");
        assertThat(valide.propositionId()).isNotNull();
        assertThat(valide.ecritureId()).isNull();

        ArticleDto apresInventaire = articleService.obtenir(entreprise.id(), article.id(), principal);
        assertThat(apresInventaire.quantiteEnStock()).isEqualByComparingTo("6");

        UUID inventaireId = inventaire.id();
        // Cloture bloquee tant que la proposition n'est pas validee par un humain.
        assertThatThrownBy(() -> inventaireService.cloturer(entreprise.id(), inventaireId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("doit etre validee");

        // 15. Validation humaine de la proposition -> ecriture reelle creee.
        AccountingOperationProposalDto propositionValidee =
                accountingOperationEngine.valider(entreprise.id(), valide.propositionId(), principal);
        assertThat(propositionValidee.statut()).isEqualTo("APPROVED");
        assertThat(propositionValidee.equilibree()).isTrue();
        assertThat(propositionValidee.totalDebit()).isEqualByComparingTo(propositionValidee.totalCredit());
        assertThat(propositionValidee.ecritureId()).isNotNull();

        InventaireDto cloture = inventaireService.cloturer(entreprise.id(), inventaire.id(), principal);
        assertThat(cloture.statut()).isEqualTo("CLOTURE");
        // 16. "Balance" : l'ecriture de regularisation est equilibree (deja verifie ci-dessus) et
        // desormais tracee sur l'inventaire.
        assertThat(cloture.ecritureId()).isEqualTo(propositionValidee.ecritureId());

        // 17-18. Isolation multi-tenant : une seconde entreprise ne voit ni l'article ni l'inventaire de la premiere.
        EntrepriseDto entrepriseB = creerEntreprise(principal, "Stock Isolation B");
        assertThatThrownBy(() -> articleService.obtenir(entrepriseB.id(), article.id(), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> inventaireService.obtenir(entrepriseB.id(), inventaireId, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThat(articleService.lister(entrepriseB.id(), principal)).isEmpty();
        assertThat(inventaireService.lister(entrepriseB.id(), principal)).isEmpty();
    }

    @Test
    void inventaire_sans_ecart_se_cloture_sans_generer_de_proposition() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entreprise = creerEntreprise(principal, "Stock Sans Ecart");

        CompteComptable compteStock = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "311").orElseThrow();
        CompteComptable compteAchat = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "601").orElseThrow();
        CompteComptable compteVente = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "701").orElseThrow();
        CompteComptable compteVariation = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "6031").orElseThrow();

        ArticleDto article = articleService.creer(entreprise.id(),
                new CreerArticleRequest("ART-TOLE", "Tole ondulee", null, TypeArticle.MARCHANDISE, "unite",
                        new BigDecimal("5000"), new BigDecimal("18"),
                        compteStock.getId(), compteAchat.getId(), compteVente.getId(), compteVariation.getId(),
                        null, null, null),
                principal);
        TiersDto fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Fournisseur Tole", null, principal);
        CommandeAchatDto commande = commandeAchatService.creer(entreprise.id(),
                new CreerCommandeAchatRequest(fournisseur.id(), LocalDate.now(),
                        List.of(new LigneCommandeAchatRequest(article.id(), new BigDecimal("5"), new BigDecimal("4000")))),
                principal);
        commandeAchatService.validerReception(entreprise.id(), commande.id(), principal);

        InventaireDto inventaire = inventaireService.creer(entreprise.id(),
                new CreerInventaireRequest(LocalDate.now(), null), principal);
        inventaire = inventaireService.saisirLigne(entreprise.id(), inventaire.id(),
                new SaisirLigneInventaireRequest(article.id(), new BigDecimal("5"), null), principal);
        assertThat(inventaire.lignes().get(0).ecart()).isEqualByComparingTo("0");

        InventaireDto valide = inventaireService.valider(entreprise.id(), inventaire.id(), principal);
        assertThat(valide.propositionId()).isNull();

        InventaireDto cloture = inventaireService.cloturer(entreprise.id(), inventaire.id(), principal);
        assertThat(cloture.statut()).isEqualTo("CLOTURE");
        assertThat(cloture.ecritureId()).isNull();
    }

    private EntrepriseDto creerEntreprise(UtilisateurPrincipal principal, String raisonSociale) {
        return entrepriseService.creer(
                new CreerEntrepriseRequest(raisonSociale, null, null, null, null, null, "CI", "XOF"),
                principal);
    }

    private UtilisateurPrincipal creerPrincipalExpertComptable() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Stock Test");
        tenant.setSlug("cabinet-stock-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("stock-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Stock");
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);

        return new UtilisateurPrincipal(utilisateur);
    }
}
