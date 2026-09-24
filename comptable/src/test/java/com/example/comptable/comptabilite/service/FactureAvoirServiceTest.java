package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.FactureAvoir;
import com.example.comptable.comptabilite.domain.LigneFacture;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.StatutFactureAvoir;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.vente.VenteRule;
import com.example.comptable.comptabilite.repository.FactureAvoirRepository;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.web.dto.CreerFactureAvoirRequest;
import com.example.comptable.comptabilite.web.dto.FactureAvoirDto;
import com.example.comptable.comptabilite.web.dto.LigneAvoirRequest;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.service.MouvementStockService;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * validerAvoir route desormais la construction des lignes via VenteRule et la
 * proposition/validation via AccountingOperationEngine.proposerEtValiderImmediatement (mission
 * cloture Etape 4, section 22) plutot que de construire une EcritureComptable a la main - ces
 * deux collaborateurs sont donc mockes ici comme des unites a part entiere.
 */
@ExtendWith(MockitoExtension.class)
class FactureAvoirServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private FactureRepository factureRepository;
    @Mock
    private FactureAvoirRepository factureAvoirRepository;
    @Mock
    private CompteComptableValidationService compteComptableValidationService;
    @Mock
    private CompteSystemeResolver compteSystemeResolver;
    @Mock
    private VenteRule venteRule;
    @Mock
    private AccountingOperationEngine accountingOperationEngine;
    @Mock
    private MouvementStockService mouvementStockService;
    @Mock
    private ArticleRepository articleRepository;
    @Mock
    private LettrageService lettrageService;

    @InjectMocks
    private FactureAvoirService factureAvoirService;

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
        compteVentes.setNumero("701");

        compteTva = new CompteComptable();
        compteTva.setId(UUID.randomUUID());
        compteTva.setEntreprise(entreprise);
        compteTva.setNumero("443");

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    /** Facture VALIDEE, 10 x 10000 HT, TVA 18% => HT 100000 / TVA 18000 / TTC 118000. */
    private Facture factureValidee() {
        Facture facture = new Facture();
        facture.setId(UUID.randomUUID());
        facture.setEntreprise(entreprise);
        facture.setTiers(client);
        facture.setType(TypeFacture.FACTURE);
        facture.setNumero("FAC-2026-0042");
        facture.setDateEmission(LocalDate.of(2026, 3, 12));
        facture.setStatut(StatutFacture.VALIDEE);
        facture.setCompteProduitId(compteVentes.getId());

        LigneFacture ligne = new LigneFacture();
        ligne.setId(UUID.randomUUID());
        ligne.setDesignation("Prestation conseil");
        ligne.setQuantite(new BigDecimal("10"));
        ligne.setPrixUnitaire(new BigDecimal("10000"));
        ligne.setTauxTva(new BigDecimal("18"));
        facture.ajouterLigne(ligne);
        return facture;
    }

    private void mockerContexteComptable() {
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteVentes.getId(), true)).thenReturn(compteVentes);
        when(compteSystemeResolver.resoudre(entrepriseId, RoleCompteSysteme.TVA_COLLECTEE)).thenReturn(Optional.of(compteTva));
        when(venteRule.construireLignesAvoirVenteDepuisMontantsPrecalcules(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(accountingOperationEngine.proposerEtValiderImmediatement(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new AccountingOperationProposalDto(UUID.randomUUID(), entrepriseId, "AVOIR_VENTE", "APPROVED",
                        null, null, client.getId(), LocalDate.now(), "libelle", null, List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                        true, List.of(), List.of(), null, null, null, null, UUID.randomUUID(), "FACTURE_AVOIR", null));
        when(lettrageService.lettrerAutomatiquement(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void genere_un_avoir_total_qui_inverse_le_compte_produit_la_tva_et_le_client() {
        Facture facture = factureValidee();
        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(factureAvoirRepository.sommeQuantitesCrediteesParLigne(any(), any())).thenReturn(List.of());
        when(factureAvoirRepository.save(any())).thenAnswer(inv -> {
            FactureAvoir a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });

        FactureAvoirDto avoirCree = factureAvoirService.creerAvoir(entrepriseId, facture.getId(),
                new CreerFactureAvoirRequest("Retour marchandise", false, true, null), principal);

        assertThat(avoirCree.statut()).isEqualTo("BROUILLON");
        assertThat(avoirCree.montantHt()).isEqualByComparingTo("100000");
        assertThat(avoirCree.montantTva()).isEqualByComparingTo("18000");
        assertThat(avoirCree.montantTtc()).isEqualByComparingTo("118000");

        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteVentes.getId(), true)).thenReturn(compteVentes);
        when(compteSystemeResolver.resoudre(entrepriseId, RoleCompteSysteme.TVA_COLLECTEE)).thenReturn(Optional.of(compteTva));
        when(venteRule.construireLignesAvoirVenteDepuisMontantsPrecalcules(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        UUID ecritureId = UUID.randomUUID();
        when(accountingOperationEngine.proposerEtValiderImmediatement(eq(entrepriseId), eq(AccountingOperationType.AVOIR_VENTE),
                eq(client), any(), any(), any(), any(), eq("FACTURE_AVOIR"), any(), eq(principal)))
                .thenReturn(new AccountingOperationProposalDto(UUID.randomUUID(), entrepriseId, "AVOIR_VENTE", "APPROVED",
                        null, null, client.getId(), facture.getDateEmission(), "libelle", null, List.of(), BigDecimal.ZERO,
                        BigDecimal.ZERO, true, List.of(), List.of(), null, null, null, null, ecritureId, "FACTURE_AVOIR", null));
        when(lettrageService.lettrerAutomatiquement(any(), any(), any())).thenReturn(List.of());

        FactureAvoir avoirEntite = construireAvoirDepuisDto(avoirCree, facture);
        when(factureAvoirRepository.findById(avoirCree.id())).thenReturn(Optional.of(avoirEntite));

        FactureAvoirDto resultat = factureAvoirService.validerAvoir(entrepriseId, avoirCree.id(), principal);

        assertThat(resultat.statut()).isEqualTo("VALIDEE");
        assertThat(resultat.ecritureId()).isEqualTo(ecritureId);

        ArgumentCaptor<BigDecimal> montantHtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<CompteComptable> compteTvaCaptor = ArgumentCaptor.forClass(CompteComptable.class);
        ArgumentCaptor<BigDecimal> montantTvaCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> montantTtcCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(venteRule).construireLignesAvoirVenteDepuisMontantsPrecalcules(eq(client), eq(compteVentes),
                montantHtCaptor.capture(), compteTvaCaptor.capture(), montantTvaCaptor.capture(), montantTtcCaptor.capture(), any());
        assertThat(montantHtCaptor.getValue()).isEqualByComparingTo("100000");
        assertThat(compteTvaCaptor.getValue().getNumero()).isEqualTo("443");
        assertThat(montantTvaCaptor.getValue()).isEqualByComparingTo("18000");
        assertThat(montantTtcCaptor.getValue()).isEqualByComparingTo("118000");

        verify(lettrageService).lettrerAutomatiquement(entrepriseId, compteClient.getId(), principal);
    }

    /** Reconstruit l'entite geree telle qu'elle serait rechargee en base, a partir du DTO obtenu
     * a la creation (le mock de save() ne persiste pas reellement) - evite de dupliquer la logique
     * de creerAvoir tout en gardant chaque test independant. */
    private FactureAvoir construireAvoirDepuisDto(FactureAvoirDto dto, Facture facture) {
        FactureAvoir avoir = new FactureAvoir();
        avoir.setId(dto.id());
        avoir.setEntreprise(entreprise);
        avoir.setFactureOrigine(facture);
        avoir.setTiers(client);
        avoir.setNumero(dto.numero());
        avoir.setDateEmission(dto.dateEmission());
        avoir.setMotif(dto.motif());
        avoir.setRetourStock(dto.retourStock());
        avoir.setStatut(StatutFactureAvoir.BROUILLON);
        dto.lignes().forEach(l -> {
            com.example.comptable.comptabilite.domain.LigneFactureAvoir ligne = new com.example.comptable.comptabilite.domain.LigneFactureAvoir();
            ligne.setLigneFactureOrigine(facture.getLignes().stream()
                    .filter(lo -> lo.getId().equals(l.ligneFactureOrigineId())).findFirst().orElseThrow());
            ligne.setDesignation(l.designation());
            ligne.setQuantite(l.quantite());
            ligne.setPrixUnitaire(l.prixUnitaire());
            ligne.setTauxTva(l.tauxTva());
            avoir.ajouterLigne(ligne);
        });
        return avoir;
    }

    @Test
    void refuse_de_creer_un_avoir_sur_une_facture_en_brouillon() {
        Facture facture = factureValidee();
        facture.setStatut(StatutFacture.BROUILLON);
        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));

        assertThatThrownBy(() -> factureAvoirService.creerAvoir(entrepriseId, facture.getId(),
                new CreerFactureAvoirRequest("Erreur", false, true, null), principal))
                .hasMessageContaining("validee ou payee");
    }

    @Test
    void avoir_partiel_ne_reprend_que_la_quantite_demandee() {
        Facture facture = factureValidee();
        UUID ligneId = facture.getLignes().get(0).getId();
        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(factureAvoirRepository.sommeQuantitesCrediteesParLigne(any(), any())).thenReturn(List.of());
        when(factureAvoirRepository.save(any())).thenAnswer(inv -> {
            FactureAvoir a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        FactureAvoirDto resultat = factureAvoirService.creerAvoir(entrepriseId, facture.getId(),
                new CreerFactureAvoirRequest("Retour partiel", false, false,
                        List.of(new LigneAvoirRequest(ligneId, new BigDecimal("4")))),
                principal);

        assertThat(resultat.lignes()).hasSize(1);
        assertThat(resultat.lignes().get(0).quantite()).isEqualByComparingTo("4");
        assertThat(resultat.montantHt()).isEqualByComparingTo("40000");
    }

    @Test
    void refuse_de_crediter_plus_que_le_reste_disponible() {
        Facture facture = factureValidee();
        UUID ligneId = facture.getLignes().get(0).getId();
        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        // 6 unites deja creditees par un avoir precedent : il ne reste que 4 unites creditables.
        when(factureAvoirRepository.sommeQuantitesCrediteesParLigne(any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{ligneId, new BigDecimal("6")}));

        assertThatThrownBy(() -> factureAvoirService.creerAvoir(entrepriseId, facture.getId(),
                new CreerFactureAvoirRequest("Retour", false, false,
                        List.of(new LigneAvoirRequest(ligneId, new BigDecimal("5")))),
                principal))
                .hasMessageContaining("superieure au restant creditable");
    }

    @Test
    void reintegre_le_stock_au_pump_courant_quand_retourStock_est_vrai() {
        Facture facture = factureValidee();
        UUID articleId = UUID.randomUUID();
        facture.getLignes().get(0).setArticleId(articleId);

        Article article = new Article();
        article.setId(articleId);
        article.setEntreprise(entreprise);
        article.setPump(new BigDecimal("1200"));

        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(factureAvoirRepository.sommeQuantitesCrediteesParLigne(any(), any())).thenReturn(List.of());
        when(factureAvoirRepository.save(any())).thenAnswer(inv -> {
            FactureAvoir a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });

        FactureAvoirDto avoirCree = factureAvoirService.creerAvoir(entrepriseId, facture.getId(),
                new CreerFactureAvoirRequest("Retour marchandise", true, true, null), principal);

        mockerContexteComptable();
        when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));
        when(mouvementStockService.enregistrerAvecSource(any(), any(), any(), eq("FACTURE_AVOIR"), any())).thenReturn(mock(MouvementStockDto.class));
        FactureAvoir avoirEntite = construireAvoirDepuisDto(avoirCree, facture);
        avoirEntite.setRetourStock(true);
        when(factureAvoirRepository.findById(avoirCree.id())).thenReturn(Optional.of(avoirEntite));

        factureAvoirService.validerAvoir(entrepriseId, avoirCree.id(), principal);

        ArgumentCaptor<CreerMouvementStockRequest> captor = ArgumentCaptor.forClass(CreerMouvementStockRequest.class);
        verify(mouvementStockService, times(1)).enregistrerAvecSource(eq(entrepriseId), captor.capture(), eq(principal), eq("FACTURE_AVOIR"), eq(avoirEntite.getId()));
        assertThat(captor.getValue().type()).isEqualTo(com.example.comptable.stock.domain.TypeMouvementStock.ENTREE);
        assertThat(captor.getValue().quantite()).isEqualByComparingTo("10");
        assertThat(captor.getValue().prixUnitaireHt()).isEqualByComparingTo("1200");
    }

    @Test
    void ne_touche_pas_au_stock_quand_retourStock_est_faux() {
        Facture facture = factureValidee();
        facture.getLignes().get(0).setArticleId(UUID.randomUUID());
        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(factureAvoirRepository.sommeQuantitesCrediteesParLigne(any(), any())).thenReturn(List.of());
        when(factureAvoirRepository.save(any())).thenAnswer(inv -> {
            FactureAvoir a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });

        FactureAvoirDto avoirCree = factureAvoirService.creerAvoir(entrepriseId, facture.getId(),
                new CreerFactureAvoirRequest("Remise accordee", false, true, null), principal);

        mockerContexteComptable();
        FactureAvoir avoirEntite = construireAvoirDepuisDto(avoirCree, facture);
        when(factureAvoirRepository.findById(avoirCree.id())).thenReturn(Optional.of(avoirEntite));

        factureAvoirService.validerAvoir(entrepriseId, avoirCree.id(), principal);

        verify(mouvementStockService, never()).enregistrer(any(), any(), any());
    }

    @Test
    void refuse_de_valider_un_avoir_deja_valide() {
        Facture facture = factureValidee();
        FactureAvoir avoir = new FactureAvoir();
        avoir.setId(UUID.randomUUID());
        avoir.setEntreprise(entreprise);
        avoir.setFactureOrigine(facture);
        avoir.setTiers(client);
        avoir.setStatut(StatutFactureAvoir.VALIDEE);
        when(factureAvoirRepository.findById(avoir.getId())).thenReturn(Optional.of(avoir));

        assertThatThrownBy(() -> factureAvoirService.validerAvoir(entrepriseId, avoir.getId(), principal))
                .hasMessageContaining("deja valide");
    }

    @Test
    void genere_un_pdf_non_vide_avec_lentete_pdf() {
        Facture facture = factureValidee();
        FactureAvoir avoir = new FactureAvoir();
        avoir.setId(UUID.randomUUID());
        avoir.setEntreprise(entreprise);
        avoir.setFactureOrigine(facture);
        avoir.setTiers(client);
        avoir.setNumero("AV-2026-0001");
        avoir.setDateEmission(LocalDate.of(2026, 3, 20));
        avoir.setMotif("Retour marchandise");
        avoir.setStatut(StatutFactureAvoir.VALIDEE);
        com.example.comptable.comptabilite.domain.LigneFactureAvoir ligne = new com.example.comptable.comptabilite.domain.LigneFactureAvoir();
        ligne.setLigneFactureOrigine(facture.getLignes().get(0));
        ligne.setDesignation("Prestation conseil");
        ligne.setQuantite(new BigDecimal("10"));
        ligne.setPrixUnitaire(new BigDecimal("10000"));
        ligne.setTauxTva(new BigDecimal("18"));
        avoir.ajouterLigne(ligne);
        when(factureAvoirRepository.findById(avoir.getId())).thenReturn(Optional.of(avoir));

        byte[] pdf = factureAvoirService.genererPdf(entrepriseId, avoir.getId(), principal);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }
}
