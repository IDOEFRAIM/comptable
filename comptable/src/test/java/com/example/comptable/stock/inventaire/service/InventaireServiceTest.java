package com.example.comptable.stock.inventaire.service;

import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.domain.StatutOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.ModeGestionStock;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.inventaire.domain.Inventaire;
import com.example.comptable.stock.inventaire.domain.LigneInventaire;
import com.example.comptable.stock.inventaire.domain.StatutInventaire;
import com.example.comptable.stock.inventaire.repository.InventaireRepository;
import com.example.comptable.stock.inventaire.repository.LigneInventaireRepository;
import com.example.comptable.stock.inventaire.web.dto.CreerInventaireRequest;
import com.example.comptable.stock.inventaire.web.dto.InventaireDto;
import com.example.comptable.stock.inventaire.web.dto.SaisirLigneInventaireRequest;
import com.example.comptable.stock.service.MouvementStockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventaireServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private InventaireRepository inventaireRepository;
    @Mock
    private LigneInventaireRepository ligneInventaireRepository;
    @Mock
    private ArticleRepository articleRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    @Mock
    private MouvementStockService mouvementStockService;
    @Mock
    private AccountingOperationEngine accountingOperationEngine;
    @Mock
    private AccountingOperationProposalRepository accountingOperationProposalRepository;

    @InjectMocks
    private InventaireService inventaireService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Utilisateur utilisateur;
    private Article article;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        article = new Article();
        article.setId(UUID.randomUUID());
        article.setEntreprise(entreprise);
        article.setCodeArticle("ART-001");
        article.setDesignation("Sac de ciment");
        article.setQuantiteEnStock(new BigDecimal("30"));
        article.setPump(new BigDecimal("120"));
        article.setActif(true);
    }

    private Inventaire inventaireBrouillon() {
        Inventaire inventaire = new Inventaire();
        inventaire.setId(UUID.randomUUID());
        inventaire.setEntreprise(entreprise);
        inventaire.setDate(LocalDate.now());
        inventaire.setStatut(StatutInventaire.BROUILLON);
        return inventaire;
    }

    @Test
    void cree_un_inventaire_en_brouillon() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.save(any())).thenAnswer(inv -> {
            Inventaire i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        InventaireDto resultat = inventaireService.creer(entrepriseId,
                new CreerInventaireRequest(LocalDate.now(), "Inventaire annuel"), principal);

        assertThat(resultat.statut()).isEqualTo("BROUILLON");
    }

    @Test
    void saisie_ligne_calcule_lecart_positif_en_mode_permanent() {
        Inventaire inventaire = inventaireBrouillon();
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));
        when(articleRepository.findById(article.getId()))
                .thenReturn(Optional.of(article).filter(a -> a.getEntreprise().getId().equals(entrepriseId)));
        when(ligneInventaireRepository.findByInventaireIdAndArticleId(inventaire.getId(), article.getId()))
                .thenReturn(Optional.empty());
        when(inventaireRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InventaireDto resultat = inventaireService.saisirLigne(entrepriseId, inventaire.getId(),
                new SaisirLigneInventaireRequest(article.getId(), new BigDecimal("35"), null), principal);

        assertThat(resultat.statut()).isEqualTo("EN_COURS");
        assertThat(resultat.lignes()).hasSize(1);
        assertThat(resultat.lignes().get(0).ecart()).isEqualByComparingTo("5");
        assertThat(resultat.lignes().get(0).valeurEcart()).isEqualByComparingTo("600");
    }

    @Test
    void saisie_ligne_calcule_lecart_negatif() {
        Inventaire inventaire = inventaireBrouillon();
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));
        when(articleRepository.findById(article.getId())).thenReturn(Optional.of(article));
        when(ligneInventaireRepository.findByInventaireIdAndArticleId(inventaire.getId(), article.getId()))
                .thenReturn(Optional.empty());
        when(inventaireRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InventaireDto resultat = inventaireService.saisirLigne(entrepriseId, inventaire.getId(),
                new SaisirLigneInventaireRequest(article.getId(), new BigDecimal("28"), null), principal);

        assertThat(resultat.lignes().get(0).ecart()).isEqualByComparingTo("-2");
        assertThat(resultat.lignes().get(0).valeurEcart()).isEqualByComparingTo("240");
    }

    @Test
    void saisie_ligne_sans_ecart() {
        Inventaire inventaire = inventaireBrouillon();
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));
        when(articleRepository.findById(article.getId())).thenReturn(Optional.of(article));
        when(ligneInventaireRepository.findByInventaireIdAndArticleId(inventaire.getId(), article.getId()))
                .thenReturn(Optional.empty());
        when(inventaireRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InventaireDto resultat = inventaireService.saisirLigne(entrepriseId, inventaire.getId(),
                new SaisirLigneInventaireRequest(article.getId(), new BigDecimal("30"), null), principal);

        assertThat(resultat.lignes().get(0).ecart()).isEqualByComparingTo("0");
    }

    @Test
    void saisie_ligne_mode_intermittent_sans_historique_ni_cout_manuel_est_refusee() {
        article.setModeGestionStock(ModeGestionStock.INTERMITTENT);
        Inventaire inventaire = inventaireBrouillon();
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));
        when(articleRepository.findById(article.getId())).thenReturn(Optional.of(article));
        when(ligneInventaireRepository.findDernieresValideesParArticle(article.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> inventaireService.saisirLigne(entrepriseId, inventaire.getId(),
                new SaisirLigneInventaireRequest(article.getId(), new BigDecimal("10"), null), principal))
                .hasMessageContaining("Cout unitaire requis");
    }

    @Test
    void saisie_ligne_mode_intermittent_sans_historique_avec_cout_manuel_est_acceptee() {
        article.setModeGestionStock(ModeGestionStock.INTERMITTENT);
        Inventaire inventaire = inventaireBrouillon();
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));
        when(articleRepository.findById(article.getId())).thenReturn(Optional.of(article));
        when(ligneInventaireRepository.findDernieresValideesParArticle(article.getId())).thenReturn(List.of());
        when(ligneInventaireRepository.findByInventaireIdAndArticleId(inventaire.getId(), article.getId()))
                .thenReturn(Optional.empty());
        when(inventaireRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InventaireDto resultat = inventaireService.saisirLigne(entrepriseId, inventaire.getId(),
                new SaisirLigneInventaireRequest(article.getId(), new BigDecimal("10"), new BigDecimal("500")), principal);

        assertThat(resultat.lignes().get(0).stockTheorique()).isEqualByComparingTo("0");
        assertThat(resultat.lignes().get(0).ecart()).isEqualByComparingTo("10");
        assertThat(resultat.lignes().get(0).valeurEcart()).isEqualByComparingTo("5000");
    }

    @Test
    void valider_sans_ligne_est_refuse() {
        Inventaire inventaire = inventaireBrouillon();
        inventaire.setStatut(StatutInventaire.EN_COURS);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));

        assertThatThrownBy(() -> inventaireService.valider(entrepriseId, inventaire.getId(), principal))
                .hasMessageContaining("Aucune ligne");
    }

    @Test
    void valider_un_inventaire_avec_ecart_enregistre_le_mouvement_et_cree_une_proposition() {
        Inventaire inventaire = inventaireBrouillon();
        inventaire.setStatut(StatutInventaire.EN_COURS);
        LigneInventaire ligne = new LigneInventaire();
        ligne.setArticle(article);
        ligne.setStockTheorique(new BigDecimal("30"));
        ligne.setStockCompte(new BigDecimal("35"));
        ligne.setEcart(new BigDecimal("5"));
        ligne.setCoutUnitaire(new BigDecimal("120"));
        ligne.setValeurEcart(new BigDecimal("600"));
        inventaire.ajouterLigne(ligne);

        UUID propositionId = UUID.randomUUID();
        AccountingOperationProposalDto propositionDto = new AccountingOperationProposalDto(propositionId, entrepriseId, "REGULARISATION_INVENTAIRE", "PROPOSED", null, null, null, inventaire.getDate(), "Regularisation", null, List.of(), new BigDecimal("600"), new BigDecimal("600"), true, List.of(), List.of(), null, null, null, null, null, null, null, null);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));
        when(accountingOperationEngine.proposerRegularisationInventaire(entrepriseId, inventaire, principal))
                .thenReturn(propositionDto);
        AccountingOperationProposal propositionEntite = new AccountingOperationProposal();
        propositionEntite.setId(propositionId);
        when(accountingOperationProposalRepository.getReferenceById(propositionId)).thenReturn(propositionEntite);
        when(inventaireRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InventaireDto resultat = inventaireService.valider(entrepriseId, inventaire.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("VALIDE");
        assertThat(resultat.propositionId()).isEqualTo(propositionId);
        verify(mouvementStockService).enregistrerRegularisationInventaire(entrepriseId, article.getId(),
                new BigDecimal("5"), new BigDecimal("120"), inventaire.getDate(),
                "Regularisation inventaire - Sac de ciment", inventaire.getId(), principal);
    }

    @Test
    void valider_un_inventaire_sans_aucun_ecart_ne_cree_ni_mouvement_ni_proposition() {
        Inventaire inventaire = inventaireBrouillon();
        inventaire.setStatut(StatutInventaire.EN_COURS);
        LigneInventaire ligne = new LigneInventaire();
        ligne.setArticle(article);
        ligne.setStockTheorique(new BigDecimal("30"));
        ligne.setStockCompte(new BigDecimal("30"));
        ligne.setEcart(BigDecimal.ZERO);
        ligne.setCoutUnitaire(new BigDecimal("120"));
        ligne.setValeurEcart(BigDecimal.ZERO);
        inventaire.ajouterLigne(ligne);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));
        when(inventaireRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InventaireDto resultat = inventaireService.valider(entrepriseId, inventaire.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("VALIDE");
        assertThat(resultat.propositionId()).isNull();
        verify(mouvementStockService, never()).enregistrerRegularisationInventaire(any(), any(), any(), any(), any(), any(), any(), any());
        verify(accountingOperationEngine, never()).proposerRegularisationInventaire(any(), any(), any());
    }

    @Test
    void cloturer_sans_proposition_approuvee_est_refuse() {
        Inventaire inventaire = inventaireBrouillon();
        inventaire.setStatut(StatutInventaire.VALIDE);
        AccountingOperationProposal proposition = new AccountingOperationProposal();
        proposition.setId(UUID.randomUUID());
        proposition.setStatut(StatutOperationProposal.PROPOSED);
        inventaire.setProposition(proposition);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));

        assertThatThrownBy(() -> inventaireService.cloturer(entrepriseId, inventaire.getId(), principal))
                .hasMessageContaining("doit etre validee");
        verify(mouvementStockService, never()).lierEcriture(any(), any(), any());
    }

    @Test
    void cloturer_sans_ecart_ni_proposition_reussit_directement() {
        Inventaire inventaire = inventaireBrouillon();
        inventaire.setStatut(StatutInventaire.VALIDE);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));
        when(inventaireRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InventaireDto resultat = inventaireService.cloturer(entrepriseId, inventaire.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("CLOTURE");
    }

    @Test
    void cloturer_un_inventaire_non_valide_est_refuse() {
        Inventaire inventaire = inventaireBrouillon();
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(inventaireRepository.findById(inventaire.getId())).thenReturn(Optional.of(inventaire));

        assertThatThrownBy(() -> inventaireService.cloturer(entrepriseId, inventaire.getId(), principal))
                .hasMessageContaining("valide peut etre cloture");
    }
}
