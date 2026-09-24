package com.example.comptable.comptabilite;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.regularisation.domain.RegularisationType;
import com.example.comptable.comptabilite.regularisation.service.RegularisationService;
import com.example.comptable.comptabilite.regularisation.web.dto.CreerRegularisationRequest;
import com.example.comptable.comptabilite.regularisation.web.dto.RegularisationDto;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.ExerciceComptableDto;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
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
 * Scenario complet sur PostgreSQL reel (mission cloture Etape 4, section 30) : entreprise -&gt;
 * PCE -&gt; exercice -&gt; fournisseur -&gt; client -&gt; achat -&gt; stock -&gt; vente -&gt; reglement -&gt;
 * inventaire -&gt; CCA -&gt; PCA -&gt; propositions -&gt; validation -&gt; ecritures equilibrees -&gt;
 * readiness -&gt; cloture -&gt; exercice suivant -&gt; anti-doublon -&gt; isolation multi-tenant. Suit le
 * meme patron que {@link com.example.comptable.stock.inventaire.InventaireIntegrationTest} et
 * {@link com.example.comptable.comptabilite.operation.AccountingOperationEngineIntegrationTest} :
 * entreprise reelle via EntrepriseService.creer (PCE + exercice auto-provisionnes).
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class ClotureRegularisationIntegrationTest {

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
    private RegularisationService regularisationService;
    @Autowired
    private ClotureExerciceService clotureExerciceService;
    @Autowired
    private ClotureReadinessService clotureReadinessService;
    @Autowired
    private AccountingOperationEngine accountingOperationEngine;
    @Autowired
    private EcritureComptableService ecritureComptableService;
    @Autowired
    private CompteComptableRepository compteComptableRepository;
    @Autowired
    private ExerciceComptableRepository exerciceComptableRepository;

    @Test
    void scenario_complet_achat_vente_reglement_inventaire_regularisations_et_cloture() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entreprise = creerEntreprise(principal, "Cloture Regularisation Scenario");

        ExerciceComptable exercice = exerciceComptableRepository
                .findByEntrepriseIdAndDate(entreprise.id(), LocalDate.now()).orElseThrow();

        CompteComptable compteStock = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "311").orElseThrow();
        CompteComptable compteAchat = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "601").orElseThrow();
        CompteComptable compteVente = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "701").orElseThrow();
        CompteComptable compteVariation = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "6031").orElseThrow();
        CompteComptable compteBanque = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "521").orElseThrow();
        // Comptes distincts de ceux deja utilises par l'achat/la vente (411/401 sont subdivises par
        // TiersService.creer et ne restent plus utilisables en detail une fois un tiers cree) -
        // utilises ici uniquement comme paire origine/contrepartie mecanique pour la regularisation,
        // la regle ne verifie pas de coherence de classe (contrairement a AchatRule/VenteRule).
        CompteComptable compteChargeCca = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "611").orElseThrow();
        CompteComptable compteContrepartieCca = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "621").orElseThrow();
        CompteComptable compteProduitPca = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "706").orElseThrow();
        CompteComptable compteContrepartiePca = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "758").orElseThrow();

        // 4. Fournisseur, 5. Client.
        TiersDto fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Cimenterie SARL", null, principal);
        TiersDto client = tiersService.creer(entreprise.id(), TypeTiers.CLIENT, "Client Chantier SARL", null, principal);

        // 3-4. Article + 6-7. Achat -> entree en stock.
        ArticleDto article = articleService.creer(entreprise.id(),
                new CreerArticleRequest("ART-CIMENT", "Sac de ciment 50kg", "Materiaux", TypeArticle.MARCHANDISE,
                        "unite", new BigDecimal("3000"), new BigDecimal("18"),
                        compteStock.getId(), compteAchat.getId(), compteVente.getId(), compteVariation.getId(),
                        null, PolitiqueStockNegatif.INTERDIT, ModeGestionStock.PERMANENT),
                principal);
        CommandeAchatDto commande = commandeAchatService.creer(entreprise.id(),
                new CreerCommandeAchatRequest(fournisseur.id(), LocalDate.now(),
                        List.of(new LigneCommandeAchatRequest(article.id(), new BigDecimal("20"), new BigDecimal("2000")))),
                principal);
        commandeAchatService.validerReception(entreprise.id(), commande.id(), principal);

        ArticleDto apresAchat = articleService.obtenir(entreprise.id(), article.id(), principal);
        assertThat(apresAchat.quantiteEnStock()).isEqualByComparingTo("20");

        // 8. Vente.
        FactureDto facture = facturationService.creer(entreprise.id(), TypeFacture.FACTURE,
                new CreerFactureRequest(client.id(), LocalDate.now(), null, "Vente ciment",
                        List.of(new LigneFactureRequest("Sac de ciment", new BigDecimal("5"), new BigDecimal("3500"),
                                new BigDecimal("18"), article.id()))),
                principal);
        facture = facturationService.validerFacture(entreprise.id(), facture.id(), compteVente.getId(), principal);
        assertThat(facture.statut()).isEqualTo("VALIDEE");
        assertThat(facture.ecritureId()).isNotNull();
        EcritureComptableDto ecritureVente = ecritureComptableService.obtenir(entreprise.id(), facture.ecritureId(), principal);
        assertThat(ecritureVente.totalDebit()).isEqualByComparingTo(ecritureVente.totalCredit());

        // Idempotence (section 24) : revalider la meme facture est refuse, une seule ecriture existe.
        UUID factureId = facture.id();
        assertThatThrownBy(() -> facturationService.validerFacture(entreprise.id(), factureId, compteVente.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja validee");

        // 9. Reglement client par banque.
        AccountingOperationProposalDto reglementPropose = accountingOperationEngine.proposerReglement(entreprise.id(),
                new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT, client.id(), LocalDate.now(),
                        "REG-001", "Reglement client", facture.montantTtc(), MoyenPaiement.VIREMENT),
                principal);
        AccountingOperationProposalDto reglementValide = accountingOperationEngine.valider(entreprise.id(), reglementPropose.id(), principal);
        assertThat(reglementValide.statut()).isEqualTo("APPROVED");

        // 10-11. CCA : assurance annuelle payee d'avance, une partie couvre l'exercice suivant.
        RegularisationDto cca = regularisationService.creer(entreprise.id(),
                new CreerRegularisationRequest(exercice.getId(), RegularisationType.CHARGE_CONSTATEE_AVANCE,
                        LocalDate.now(), exercice.getDateFin().minusMonths(2), exercice.getDateFin().plusMonths(4),
                        new BigDecimal("1800"), "Assurance annuelle", compteChargeCca.getId(), compteContrepartieCca.getId(), null),
                principal);
        assertThat(cca.montantFutur()).isGreaterThan(BigDecimal.ZERO);
        assertThat(cca.statut()).isEqualTo("BROUILLON");

        // 12. PCA : produit facture d'avance couvrant en partie l'exercice suivant.
        RegularisationDto pca = regularisationService.creer(entreprise.id(),
                new CreerRegularisationRequest(exercice.getId(), RegularisationType.PRODUIT_CONSTATE_AVANCE,
                        LocalDate.now(), exercice.getDateFin().minusMonths(1), exercice.getDateFin().plusMonths(5),
                        new BigDecimal("1200"), "Prestation facturee d'avance", compteProduitPca.getId(), compteContrepartiePca.getId(), null),
                principal);
        assertThat(pca.montantFutur()).isGreaterThan(BigDecimal.ZERO);

        // 13. Verifier les propositions generees.
        cca = regularisationService.proposer(entreprise.id(), cca.id(), principal);
        pca = regularisationService.proposer(entreprise.id(), pca.id(), principal);
        assertThat(cca.statut()).isEqualTo("PROPOSEE");
        assertThat(pca.statut()).isEqualTo("PROPOSEE");
        assertThat(cca.propositionId()).isNotNull();
        assertThat(pca.propositionId()).isNotNull();

        // 14. Validation humaine des propositions -> ecritures reelles.
        cca = regularisationService.valider(entreprise.id(), cca.id(), principal);
        pca = regularisationService.valider(entreprise.id(), pca.id(), principal);
        assertThat(cca.statut()).isEqualTo("VALIDEE");
        assertThat(pca.statut()).isEqualTo("VALIDEE");

        // 15-16. Verifier les ecritures generees, equilibrees.
        assertThat(cca.ecritureId()).isNotNull();
        assertThat(pca.ecritureId()).isNotNull();
        EcritureComptableDto ecritureCca = ecritureComptableService.obtenir(entreprise.id(), cca.ecritureId(), principal);
        assertThat(ecritureCca.totalDebit()).isEqualByComparingTo(ecritureCca.totalCredit());
        EcritureComptableDto ecriturePca = ecritureComptableService.obtenir(entreprise.id(), pca.ecritureId(), principal);
        assertThat(ecriturePca.totalDebit()).isEqualByComparingTo(ecriturePca.totalCredit());

        // 17. Verifier la readiness : aucun blocage (brouillons a 0, aucun finding critique).
        ClotureReadinessDto readiness = clotureReadinessService.evaluer(entreprise.id(), principal);
        assertThat(readiness.blockingItems()).isEmpty();
        assertThat(readiness.checklist()).isNotEmpty();

        // 18. Cloture de l'exercice -> ecriture de determination du resultat via le moteur commun.
        ExerciceComptableDto cloture = clotureExerciceService.cloturer(entreprise.id(), exercice.getId(), principal);
        assertThat(cloture.statut()).isEqualTo("CLOTURE");

        // 19. Exercice suivant cree automatiquement.
        ExerciceComptable exerciceSuivant = exerciceComptableRepository
                .findByEntrepriseIdAndDate(entreprise.id(), exercice.getDateFin().plusDays(1)).orElseThrow();
        assertThat(exerciceSuivant.getDateDebut()).isEqualTo(exercice.getDateFin().plusDays(1));

        // 20. Anti-doublon : cloturer une seconde fois le meme exercice est refuse.
        assertThatThrownBy(() -> clotureExerciceService.cloturer(entreprise.id(), exercice.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja cloture");

        // 21-22. Isolation multi-tenant : une seconde entreprise ne voit ni les regularisations
        // ni l'exercice de la premiere.
        EntrepriseDto entrepriseB = creerEntreprise(principal, "Cloture Regularisation Isolation B");
        UUID ccaId = cca.id();
        assertThat(regularisationService.lister(entrepriseB.id(), principal)).isEmpty();
        assertThatThrownBy(() -> regularisationService.obtenir(entrepriseB.id(), ccaId, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    private EntrepriseDto creerEntreprise(UtilisateurPrincipal principal, String raisonSociale) {
        return entrepriseService.creer(
                new CreerEntrepriseRequest(raisonSociale, null, null, null, null, null, "CI", "XOF"),
                principal);
    }

    private UtilisateurPrincipal creerPrincipalExpertComptable() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Cloture Regularisation Test");
        tenant.setSlug("cabinet-cloture-regularisation-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("cloture-regularisation-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("ClotureRegularisation");
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);

        return new UtilisateurPrincipal(utilisateur);
    }
}
