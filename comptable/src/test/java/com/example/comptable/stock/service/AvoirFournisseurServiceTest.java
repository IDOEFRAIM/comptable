package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.operation.common.AccountingSource;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.achat.AchatRule;
import com.example.comptable.comptabilite.operation.avoir.AvoirRule;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.AvoirFournisseur;
import com.example.comptable.stock.domain.CommandeAchat;
import com.example.comptable.stock.domain.FactureFournisseur;
import com.example.comptable.stock.domain.LigneCommandeAchat;
import com.example.comptable.stock.domain.ModeGestionStock;
import com.example.comptable.stock.domain.StatutCommandeAchat;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.repository.AvoirFournisseurRepository;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import com.example.comptable.stock.web.dto.CreerAvoirFournisseurRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

@ExtendWith(MockitoExtension.class)
class AvoirFournisseurServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private FactureFournisseurRepository factureFournisseurRepository;
    @Mock
    private AvoirFournisseurRepository avoirFournisseurRepository;
    @Mock
    private AchatRule achatRule;
    @Mock
    private AccountingOperationEngine accountingOperationEngine;
    @Mock
    private MouvementStockService mouvementStockService;

    // Inversion pure (voir AvoirRule.inverserLignes) : instance reelle, ses autres dependances ne
    // sont jamais sollicitees par cette methode.
    private final AvoirRule avoirRule = new AvoirRule(null, null, null);

    private AvoirFournisseurService avoirFournisseurService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Tiers fournisseur;
    private Article article;
    private CompteComptable compteAchat;
    private CompteComptable compteFournisseur;
    private CommandeAchat commande;
    private FactureFournisseur facture;

    @BeforeEach
    void setUp() {
        avoirFournisseurService = new AvoirFournisseurService(entrepriseAccessService, factureFournisseurRepository,
                avoirFournisseurRepository, achatRule, avoirRule, accountingOperationEngine, mouvementStockService);

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

        compteFournisseur = new CompteComptable();
        compteFournisseur.setId(UUID.randomUUID());
        compteFournisseur.setEntreprise(entreprise);
        compteFournisseur.setNumero("401001");

        fournisseur = new Tiers();
        fournisseur.setId(UUID.randomUUID());
        fournisseur.setEntreprise(entreprise);
        fournisseur.setType(TypeTiers.FOURNISSEUR);
        fournisseur.setNom("Fournisseur Ciments SA");
        fournisseur.setCompteComptable(compteFournisseur);

        compteAchat = new CompteComptable();
        compteAchat.setId(UUID.randomUUID());
        compteAchat.setEntreprise(entreprise);
        compteAchat.setNumero("601");

        article = new Article();
        article.setId(UUID.randomUUID());
        article.setEntreprise(entreprise);
        article.setCodeArticle("ART-001");
        article.setDesignation("Sac de ciment 50kg");
        article.setTauxTva(new BigDecimal("18"));
        article.setCompteAchat(compteAchat);
        article.setModeGestionStock(ModeGestionStock.PERMANENT);

        commande = new CommandeAchat();
        commande.setId(UUID.randomUUID());
        commande.setEntreprise(entreprise);
        commande.setFournisseur(fournisseur);
        commande.setNumero("BC-2026-0001");
        commande.setDateCommande(LocalDate.of(2026, 3, 1));
        commande.setStatut(StatutCommandeAchat.FACTUREE);
        LigneCommandeAchat ligne = new LigneCommandeAchat();
        ligne.setArticle(article);
        ligne.setQuantite(new BigDecimal("10"));
        ligne.setPrixUnitaireHt(new BigDecimal("1000"));
        commande.ajouterLigne(ligne);

        facture = new FactureFournisseur();
        facture.setId(UUID.randomUUID());
        facture.setEntreprise(entreprise);
        facture.setCommande(commande);
        facture.setFournisseur(fournisseur);
        facture.setNumero("FF-2026-0001");
        facture.setDateFacture(LocalDate.of(2026, 3, 2));
        facture.setMontantHt(new BigDecimal("10000"));
        facture.setMontantTva(new BigDecimal("1800"));
        facture.setMontantTtc(new BigDecimal("11800"));
        facture.setEcritureId(UUID.randomUUID());

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    @Test
    void creer_un_avoir_total_inverse_les_lignes_dorigine_et_produit_une_ecriture() {
        when(factureFournisseurRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(avoirFournisseurRepository.existsByFactureId(facture.getId())).thenReturn(false);
        when(avoirFournisseurRepository.countByEntrepriseId(entrepriseId)).thenReturn(0L);
        when(avoirFournisseurRepository.save(any())).thenAnswer(inv -> {
            AvoirFournisseur a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });

        AccountingOperationProposalLigne ligneChargeOrigine = AccountingOperationProposalLigne.debit(compteAchat, new BigDecimal("10000"), "libelle");
        AccountingOperationProposalLigne ligneFournisseurOrigine = AccountingOperationProposalLigne.credit(compteFournisseur, new BigDecimal("10000"), "libelle");
        ResultatRegle resultatOrigine = new ResultatRegle(TypeJournal.ACHATS,
                List.of(ligneChargeOrigine, ligneFournisseurOrigine), List.of(), List.of());
        when(achatRule.construireMultiLigne(eq(entreprise), eq(fournisseur), any())).thenReturn(resultatOrigine);

        UUID ecritureId = UUID.randomUUID();
        AccountingOperationProposalDto proposition = new AccountingOperationProposalDto(UUID.randomUUID(), entrepriseId, "AVOIR_ACHAT", "APPROVED", null, null, fournisseur.getId(), LocalDate.now(), "libelle", null, List.of(), new BigDecimal("10000"), new BigDecimal("10000"), true, List.of(), List.of(), null, null, null, null, ecritureId, "AVOIR_FACTURE_FOURNISSEUR", null, "VALIDATION");
        when(accountingOperationEngine.proposerEtValiderImmediatement(eq(entrepriseId), eq(AccountingOperationType.AVOIR_ACHAT), eq(fournisseur), any(), any(), any(), any(), any(AccountingSource.class), eq(principal)))
                .thenReturn(proposition);

        var resultat = avoirFournisseurService.creer(entrepriseId, facture.getId(),
                new CreerAvoirFournisseurRequest("Retour marchandise defectueuse", false), principal);

        assertThat(resultat.numero()).isEqualTo("AF-2026-0001");
        assertThat(resultat.montantHt()).isEqualByComparingTo("10000");
        assertThat(resultat.montantTtc()).isEqualByComparingTo("11800");
        assertThat(resultat.ecritureId()).isEqualTo(ecritureId);

        ArgumentCaptor<ResultatRegle> captor = ArgumentCaptor.forClass(ResultatRegle.class);
        verify(accountingOperationEngine).proposerEtValiderImmediatement(eq(entrepriseId), eq(AccountingOperationType.AVOIR_ACHAT), eq(fournisseur), any(), any(), any(), captor.capture(), any(AccountingSource.class), eq(principal));
        ResultatRegle resultatInverse = captor.getValue();
        assertThat(resultatInverse.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("601");
            assertThat(l.getCredit()).isEqualByComparingTo("10000");
        });
        assertThat(resultatInverse.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("401001");
            assertThat(l.getDebit()).isEqualByComparingTo("10000");
        });
        verify(mouvementStockService, never()).enregistrerAvecSource(any(), any(), any(), any(), any());
    }

    @Test
    void refuse_de_creer_un_second_avoir_pour_la_meme_facture() {
        when(factureFournisseurRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(avoirFournisseurRepository.existsByFactureId(facture.getId())).thenReturn(true);

        assertThatThrownBy(() -> avoirFournisseurService.creer(entrepriseId, facture.getId(),
                new CreerAvoirFournisseurRequest(null, false), principal))
                .hasMessageContaining("deja");
    }

    @Test
    void retour_stock_declenche_une_sortie_pour_chaque_article_permanent() {
        when(factureFournisseurRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(avoirFournisseurRepository.existsByFactureId(facture.getId())).thenReturn(false);
        when(avoirFournisseurRepository.countByEntrepriseId(entrepriseId)).thenReturn(0L);
        when(avoirFournisseurRepository.save(any())).thenAnswer(inv -> {
            AvoirFournisseur a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });
        when(achatRule.construireMultiLigne(any(), any(), any())).thenReturn(new ResultatRegle(TypeJournal.ACHATS, List.of(), List.of(), List.of()));
        when(accountingOperationEngine.proposerEtValiderImmediatement(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AccountingOperationProposalDto(UUID.randomUUID(), entrepriseId, "AVOIR_ACHAT", "APPROVED", null, null, fournisseur.getId(), LocalDate.now(), "libelle", null, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, List.of(), List.of(), null, null, null, null, UUID.randomUUID(), "AVOIR_FACTURE_FOURNISSEUR", null, "VALIDATION"));
        when(mouvementStockService.enregistrerAvecSource(any(), any(), any(), eq("AVOIR_FOURNISSEUR"), any()))
                .thenReturn(mock(MouvementStockDto.class));

        avoirFournisseurService.creer(entrepriseId, facture.getId(),
                new CreerAvoirFournisseurRequest(null, true), principal);

        ArgumentCaptor<com.example.comptable.stock.web.dto.CreerMouvementStockRequest> captor =
                ArgumentCaptor.forClass(com.example.comptable.stock.web.dto.CreerMouvementStockRequest.class);
        verify(mouvementStockService, times(1)).enregistrerAvecSource(eq(entrepriseId), captor.capture(),
                eq(principal), eq("AVOIR_FOURNISSEUR"), any());
        assertThat(captor.getValue().type()).isEqualTo(TypeMouvementStock.SORTIE);
        assertThat(captor.getValue().quantite()).isEqualByComparingTo("10");
    }

    @Test
    void retour_stock_delegue_le_cas_intermittent_au_service_mouvement() {
        // Le tri PERMANENT/INTERMITTENT vit desormais dans MouvementStockService.enregistrerAvecSource
        // (retourne null pour un article INTERMITTENT). Cote orchestrateur, on verifie seulement que
        // l'appel est bien delegue et qu'un retour null ne fait pas echouer la creation de l'avoir.
        article.setModeGestionStock(ModeGestionStock.INTERMITTENT);
        when(factureFournisseurRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(avoirFournisseurRepository.existsByFactureId(facture.getId())).thenReturn(false);
        when(avoirFournisseurRepository.countByEntrepriseId(entrepriseId)).thenReturn(0L);
        when(avoirFournisseurRepository.save(any())).thenAnswer(inv -> {
            AvoirFournisseur a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });
        when(achatRule.construireMultiLigne(any(), any(), any())).thenReturn(new ResultatRegle(TypeJournal.ACHATS, List.of(), List.of(), List.of()));
        when(accountingOperationEngine.proposerEtValiderImmediatement(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AccountingOperationProposalDto(UUID.randomUUID(), entrepriseId, "AVOIR_ACHAT", "APPROVED", null, null, fournisseur.getId(), LocalDate.now(), "libelle", null, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, List.of(), List.of(), null, null, null, null, UUID.randomUUID(), "AVOIR_FACTURE_FOURNISSEUR", null, "VALIDATION"));
        when(mouvementStockService.enregistrerAvecSource(any(), any(), any(), eq("AVOIR_FOURNISSEUR"), any()))
                .thenReturn(null);

        avoirFournisseurService.creer(entrepriseId, facture.getId(),
                new CreerAvoirFournisseurRequest(null, true), principal);

        verify(mouvementStockService, times(1)).enregistrerAvecSource(eq(entrepriseId), any(),
                eq(principal), eq("AVOIR_FOURNISSEUR"), any());
    }
}
