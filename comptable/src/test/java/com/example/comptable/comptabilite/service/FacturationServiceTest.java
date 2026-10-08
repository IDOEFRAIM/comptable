package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.operation.common.AccountingSource;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.vente.VenteRule;
import com.example.comptable.comptabilite.parametrage.AccountResolutionSource;
import com.example.comptable.comptabilite.parametrage.AccountResolutionStatus;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.NiveauConfiance;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.MouvementStock;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.service.MouvementStockService;
import com.example.comptable.stock.service.StockInsuffisantException;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * validerFacture route desormais la construction des lignes via VenteRule et la
 * proposition/validation via AccountingOperationEngine.proposerEtValiderImmediatement (mission
 * cloture Etape 4, section 20) plutot que de construire une EcritureComptable a la main - ces
 * deux collaborateurs sont donc mockes ici comme des unites a part entiere (chacun teste ailleurs
 * : VenteRuleTest, AccountingOperationEngineTest) plutot que re-testes en detail depuis ce fichier.
 */
@ExtendWith(MockitoExtension.class)
class FacturationServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private FactureRepository factureRepository;
    @Mock
    private TiersRepository tiersRepository;
    @Mock
    private CompteComptableValidationService compteComptableValidationService;
    @Mock
    private AccountingAccountResolver accountingAccountResolver;
    @Mock
    private VenteRule venteRule;
    @Mock
    private AccountingOperationEngine accountingOperationEngine;
    @Mock
    private MouvementStockService mouvementStockService;

    @Mock
    private com.example.comptable.comptabilite.allocation.StatutPaiementFactureService statutPaiementFactureService;

    @Mock
    private com.example.comptable.comptabilite.repository.LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private com.example.comptable.comptabilite.repository.FactureAvoirRepository factureAvoirRepository;

    @InjectMocks
    private FacturationService facturationService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Tiers client;
    private CompteComptable compteClient;
    private CompteComptable compteVentes;
    private CompteComptable compteTva;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        compteClient = new CompteComptable();
        compteClient.setId(UUID.randomUUID());
        compteClient.setEntreprise(entreprise);
        compteClient.setNumero("411001");

        client = new Tiers();
        client.setId(UUID.randomUUID());
        client.setEntreprise(entreprise);
        client.setType(TypeTiers.CLIENT);
        client.setNom("Client Fidele SARL");
        client.setCompteComptable(compteClient);

        compteVentes = new CompteComptable();
        compteVentes.setId(UUID.randomUUID());
        compteVentes.setEntreprise(entreprise);
        compteVentes.setNumero("706");

        compteTva = new CompteComptable();
        compteTva.setId(UUID.randomUUID());
        compteTva.setEntreprise(entreprise);
        compteTva.setNumero("443");

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private CreerFactureRequest requete() {
        return new CreerFactureRequest(client.getId(), LocalDate.of(2027, 3, 10), LocalDate.of(2027, 4, 10), "Prestation conseil",
                List.of(new LigneFactureRequest("Prestation conseil", new BigDecimal("10"), new BigDecimal("10000"), new BigDecimal("18"), null)));
    }

    private Facture facture(TypeFacture type, StatutFacture statut) {
        Facture f = new Facture();
        f.setId(UUID.randomUUID());
        f.setEntreprise(entreprise);
        f.setTiers(client);
        f.setType(type);
        f.setNumero("FAC-2027-0001");
        f.setDateEmission(LocalDate.of(2027, 3, 10));
        f.setStatut(statut);
        var ligne = new com.example.comptable.comptabilite.domain.LigneFacture();
        ligne.setDesignation("Prestation");
        ligne.setQuantite(new BigDecimal("10"));
        ligne.setPrixUnitaire(new BigDecimal("10000"));
        ligne.setTauxTva(new BigDecimal("18"));
        f.ajouterLigne(ligne);
        return f;
    }

    private AccountingOperationProposalDto propositionApprouvee(Facture facture, UUID ecritureId) {
        return new AccountingOperationProposalDto(UUID.randomUUID(), entrepriseId, "VENTE", "APPROVED", null, null, client.getId(), facture.getDateEmission(), "Facture " + facture.getNumero(), facture.getNumero(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, List.of(), List.of(), null, null, null, null, ecritureId, "FACTURE", facture.getId(), "VALIDATION");
    }

    @Test
    void cree_un_devis_avec_montants_calcules() {
        when(tiersRepository.findById(client.getId())).thenReturn(Optional.of(client));
        when(factureRepository.countByEntrepriseIdAndType(entrepriseId, TypeFacture.DEVIS)).thenReturn(0L);
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.creer(entrepriseId, TypeFacture.DEVIS, requete(), principal);

        assertThat(resultat.numero()).startsWith("DEV-").endsWith("-0001");
        assertThat(resultat.montantHt()).isEqualByComparingTo("100000");
        assertThat(resultat.montantTva()).isEqualByComparingTo("18000");
        assertThat(resultat.montantTtc()).isEqualByComparingTo("118000");
    }

    @Test
    void valide_une_facture_et_genere_une_ecriture_equilibree() {
        Facture facture = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);
        UUID ecritureId = UUID.randomUUID();

        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteVentes.getId(), true)).thenReturn(compteVentes);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.TVA_COLLECTEE), any(), isNull()))
                .thenReturn(resoluTva(compteTva));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteTva.getId(), true)).thenReturn(compteTva);
        when(venteRule.construireLignesVenteDepuisMontantsPrecalcules(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(accountingOperationEngine.proposerEtValiderImmediatement(eq(entrepriseId), eq(AccountingOperationType.VENTE),
                eq(client), eq(facture.getDateEmission()), any(), eq(facture.getNumero()), any(), eq(AccountingSource.de("FACTURE", facture.getId(), "VALIDATION")), eq(principal)))
                .thenReturn(propositionApprouvee(facture, ecritureId));
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.validerFacture(entrepriseId, facture.getId(), compteVentes.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("VALIDEE");
        assertThat(resultat.ecritureId()).isEqualTo(ecritureId);
    }

    @Test
    void refuse_de_valider_une_facture_deja_validee() {
        Facture facture = facture(TypeFacture.FACTURE, StatutFacture.VALIDEE);
        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));

        assertThatThrownBy(() -> facturationService.validerFacture(entrepriseId, facture.getId(), compteVentes.getId(), principal))
                .hasMessageContaining("deja validee");
    }

    @Test
    void convertit_un_devis_accepte_en_facture() {
        Facture devis = facture(TypeFacture.DEVIS, StatutFacture.ACCEPTE);
        when(factureRepository.findById(devis.getId())).thenReturn(Optional.of(devis));
        when(factureRepository.countByEntrepriseIdAndType(entrepriseId, TypeFacture.FACTURE)).thenReturn(0L);
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.convertirEnFacture(entrepriseId, devis.getId(), principal);

        assertThat(resultat.type()).isEqualTo("FACTURE");
        assertThat(resultat.devisOrigineId()).isEqualTo(devis.getId());
        assertThat(resultat.lignes()).hasSize(1);
        assertThat(devis.getStatut()).isEqualTo(StatutFacture.CONVERTI);
    }

    @Test
    void refuse_de_convertir_un_devis_non_accepte() {
        Facture devis = facture(TypeFacture.DEVIS, StatutFacture.ENVOYE);
        when(factureRepository.findById(devis.getId())).thenReturn(Optional.of(devis));

        assertThatThrownBy(() -> facturationService.convertirEnFacture(entrepriseId, devis.getId(), principal))
                .hasMessageContaining("accepte");
    }

    @Test
    void envoie_un_devis_en_brouillon() {
        Facture devis = facture(TypeFacture.DEVIS, StatutFacture.BROUILLON);
        when(factureRepository.findById(devis.getId())).thenReturn(Optional.of(devis));
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.envoyer(entrepriseId, devis.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("ENVOYE");
    }

    @Test
    void refuse_denvoyer_un_devis_deja_envoye() {
        Facture devis = facture(TypeFacture.DEVIS, StatutFacture.ENVOYE);
        when(factureRepository.findById(devis.getId())).thenReturn(Optional.of(devis));

        assertThatThrownBy(() -> facturationService.envoyer(entrepriseId, devis.getId(), principal))
                .hasMessageContaining("brouillon");
    }

    @Test
    void accepte_un_devis_envoye() {
        Facture devis = facture(TypeFacture.DEVIS, StatutFacture.ENVOYE);
        when(factureRepository.findById(devis.getId())).thenReturn(Optional.of(devis));
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.accepterDevis(entrepriseId, devis.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("ACCEPTE");
    }

    @Test
    void refuse_un_devis_envoye() {
        Facture devis = facture(TypeFacture.DEVIS, StatutFacture.ENVOYE);
        when(factureRepository.findById(devis.getId())).thenReturn(Optional.of(devis));
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.refuserDevis(entrepriseId, devis.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("REFUSE");
    }

    @Test
    void refuse_daccepter_une_facture_qui_nest_pas_un_devis() {
        Facture fact = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);
        when(factureRepository.findById(fact.getId())).thenReturn(Optional.of(fact));

        assertThatThrownBy(() -> facturationService.accepterDevis(entrepriseId, fact.getId(), principal))
                .hasMessageContaining("n'est pas un devis");
    }

    @Test
    void refuse_de_valider_une_facture_dont_lexercice_est_cloture() {
        Facture fact = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);

        when(factureRepository.findById(fact.getId())).thenReturn(Optional.of(fact));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteVentes.getId(), true)).thenReturn(compteVentes);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.TVA_COLLECTEE), any(), isNull()))
                .thenReturn(resoluTva(compteTva));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteTva.getId(), true)).thenReturn(compteTva);
        when(venteRule.construireLignesVenteDepuisMontantsPrecalcules(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(accountingOperationEngine.proposerEtValiderImmediatement(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'exercice couvrant cette date est cloture"));

        assertThatThrownBy(() -> facturationService.validerFacture(entrepriseId, fact.getId(), compteVentes.getId(), principal))
                .hasMessageContaining("cloture");
    }

    @Test
    void refuse_de_creer_une_facture_pour_un_tiers_introuvable() {
        when(tiersRepository.findById(client.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facturationService.creer(entrepriseId, TypeFacture.DEVIS, requete(), principal))
                .hasMessageContaining("Tiers introuvable");
    }

    @Test
    void refuse_daccecer_a_une_facture_dune_autre_entreprise() {
        Facture fact = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);
        fact.setEntreprise(new Entreprise());
        fact.getEntreprise().setId(UUID.randomUUID());
        when(factureRepository.findById(fact.getId())).thenReturn(Optional.of(fact));

        assertThatThrownBy(() -> facturationService.envoyer(entrepriseId, fact.getId(), principal))
                .hasMessageContaining("Introuvable");
    }

    @Test
    void valide_une_facture_avec_ligne_article_declenche_une_sortie_de_stock() {
        Facture facture = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);
        UUID articleId = UUID.randomUUID();
        facture.getLignes().get(0).setArticleId(articleId);
        UUID mouvementId = UUID.randomUUID();
        UUID ecritureId = UUID.randomUUID();
        Article article = new Article();
        article.setId(articleId);
        article.setCodeArticle("ART-001");

        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteVentes.getId(), true)).thenReturn(compteVentes);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.TVA_COLLECTEE), any(), isNull()))
                .thenReturn(resoluTva(compteTva));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteTva.getId(), true)).thenReturn(compteTva);
        when(venteRule.construireLignesVenteDepuisMontantsPrecalcules(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(accountingOperationEngine.proposerEtValiderImmediatement(eq(entrepriseId), eq(AccountingOperationType.VENTE),
                eq(client), eq(facture.getDateEmission()), any(), eq(facture.getNumero()), any(), eq(AccountingSource.de("FACTURE", facture.getId(), "VALIDATION")), eq(principal)))
                .thenReturn(propositionApprouvee(facture, ecritureId));
        when(mouvementStockService.enregistrerAvecSource(eq(entrepriseId), any(), eq(principal), eq("FACTURE"), eq(facture.getId())))
                .thenReturn(new MouvementStockDto(
                        mouvementId, articleId, "ART-001", "SORTIE_VENTE", new BigDecimal("10"), new BigDecimal("1200"),
                        new BigDecimal("12000"), new BigDecimal("5"), new BigDecimal("1200"), facture.getDateEmission(),
                        "Vente FAC-2027-0001", UUID.randomUUID(), "FACTURE", facture.getId(), null, null, null));
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.validerFacture(entrepriseId, facture.getId(), compteVentes.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("VALIDEE");
        assertThat(resultat.lignes().get(0).mouvementStockId()).isEqualTo(mouvementId);

        ArgumentCaptor<CreerMouvementStockRequest> captor = ArgumentCaptor.forClass(CreerMouvementStockRequest.class);
        verify(mouvementStockService, times(1)).enregistrerAvecSource(eq(entrepriseId), captor.capture(), eq(principal), eq("FACTURE"), eq(facture.getId()));
        assertThat(captor.getValue().articleId()).isEqualTo(articleId);
        assertThat(captor.getValue().type()).isEqualTo(TypeMouvementStock.SORTIE_VENTE);
        assertThat(captor.getValue().quantite()).isEqualByComparingTo("10");
    }

    @Test
    void propage_lexception_stock_insuffisant_lors_de_la_validation() {
        Facture facture = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);
        UUID articleId = UUID.randomUUID();
        facture.getLignes().get(0).setArticleId(articleId);
        UUID ecritureId = UUID.randomUUID();
        Article article = new Article();
        article.setId(articleId);
        article.setCodeArticle("ART-001");

        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteVentes.getId(), true)).thenReturn(compteVentes);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.TVA_COLLECTEE), any(), isNull()))
                .thenReturn(resoluTva(compteTva));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteTva.getId(), true)).thenReturn(compteTva);
        when(venteRule.construireLignesVenteDepuisMontantsPrecalcules(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(accountingOperationEngine.proposerEtValiderImmediatement(eq(entrepriseId), eq(AccountingOperationType.VENTE),
                eq(client), eq(facture.getDateEmission()), any(), eq(facture.getNumero()), any(), eq(AccountingSource.de("FACTURE", facture.getId(), "VALIDATION")), eq(principal)))
                .thenReturn(propositionApprouvee(facture, ecritureId));
        when(mouvementStockService.enregistrerAvecSource(eq(entrepriseId), any(), eq(principal), eq("FACTURE"), eq(facture.getId())))
                .thenThrow(new StockInsuffisantException("ART-001", new BigDecimal("2"), new BigDecimal("10")));

        assertThatThrownBy(() -> facturationService.validerFacture(entrepriseId, facture.getId(), compteVentes.getId(), principal))
                .isInstanceOf(StockInsuffisantException.class)
                .hasMessageContaining("Stock insuffisant");
    }

    @Test
    void annule_une_facture_validee_et_reintegre_le_stock() {
        Facture facture = facture(TypeFacture.FACTURE, StatutFacture.VALIDEE);
        UUID mouvementId = UUID.randomUUID();
        facture.getLignes().get(0).setMouvementStockId(mouvementId);

        Article article = new Article();
        article.setId(UUID.randomUUID());
        article.setCodeArticle("ART-001");

        MouvementStock sortieOrigine = new MouvementStock();
        sortieOrigine.setId(mouvementId);
        sortieOrigine.setArticle(article);
        sortieOrigine.setQuantite(new BigDecimal("10"));
        sortieOrigine.setPrixUnitaireHt(new BigDecimal("1200"));

        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(mouvementStockService.chargerEntite(entrepriseId, mouvementId)).thenReturn(sortieOrigine);
        when(mouvementStockService.enregistrerAvecSource(eq(entrepriseId), any(), eq(principal), eq("FACTURE"), eq(facture.getId())))
                .thenReturn(mock(MouvementStockDto.class));
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.annulerFacture(entrepriseId, facture.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("ANNULEE");

        ArgumentCaptor<CreerMouvementStockRequest> captor = ArgumentCaptor.forClass(CreerMouvementStockRequest.class);
        verify(mouvementStockService, times(1)).enregistrerAvecSource(eq(entrepriseId), captor.capture(), eq(principal), eq("FACTURE"), eq(facture.getId()));
        assertThat(captor.getValue().type()).isEqualTo(TypeMouvementStock.ENTREE);
        assertThat(captor.getValue().quantite()).isEqualByComparingTo("10");
        assertThat(captor.getValue().prixUnitaireHt()).isEqualByComparingTo("1200");
    }

    @Test
    void refuse_dannuler_une_facture_non_validee() {
        Facture facture = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);
        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));

        assertThatThrownBy(() -> facturationService.annulerFacture(entrepriseId, facture.getId(), principal))
                .hasMessageContaining("validee");
    }

    private ResolvedAccount resoluTva(CompteComptable compte) {
        return new ResolvedAccount(AccountResolutionStatus.RESOLVED, compte.getId(), compte.getNumero(), compte.getIntitule(),
                AccountResolutionSource.ROLE_SYSTEME, NiveauConfiance.CERTAIN, "resolu (test)");
    }
}
