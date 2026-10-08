package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.operation.common.AccountingSource;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.repository.MouvementStockRepository;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.stock.domain.MouvementStock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MouvementStockServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private ArticleRepository articleRepository;
    @Mock
    private MouvementStockRepository mouvementStockRepository;
    @Mock
    private com.example.comptable.comptabilite.operation.common.AccountingOperationEngine accountingOperationEngine;
    @Mock
    private com.example.comptable.comptabilite.operation.stock.StockMouvementRule stockMouvementRule;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    /** Instance reelle (pas un mock) : le calcul CUMP doit rester exactement celui deja teste par
     * PumpCalculatorTest, seul le point d'injection a change. */
    @Spy
    private StockValuationService stockValuationService = new CumpValuationService();

    @InjectMocks
    private MouvementStockService mouvementStockService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Article article;
    private Journal journalOd;
    private ExerciceComptable exercice;
    private Utilisateur utilisateurCourant;

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
        utilisateurCourant = utilisateur;

        CompteComptable compteStock = compte("311", "Marchandises");
        CompteComptable compteAchat = compte("601", "Achats de marchandises");
        CompteComptable compteVente = compte("701", "Ventes de marchandises");
        CompteComptable compteVariation = compte("6031", "Variation de stocks de marchandises");

        article = new Article();
        article.setId(UUID.randomUUID());
        article.setEntreprise(entreprise);
        article.setCodeArticle("ART-001");
        article.setDesignation("Sac de ciment 50kg");
        article.setUniteMesure("unite");
        article.setPrixVenteHt(new BigDecimal("2000"));
        article.setTauxTva(new BigDecimal("18"));
        article.setQuantiteEnStock(BigDecimal.ZERO);
        article.setValeurStockTotale(BigDecimal.ZERO);
        article.setPump(BigDecimal.ZERO);
        article.setCompteStock(compteStock);
        article.setCompteAchat(compteAchat);
        article.setCompteVente(compteVente);
        article.setCompteVariation(compteVariation);

        journalOd = new Journal();
        journalOd.setId(UUID.randomUUID());
        journalOd.setEntreprise(entreprise);
        journalOd.setCode("OD");
        journalOd.setLibelle("Operations diverses");
        journalOd.setType(TypeJournal.OPERATIONS_DIVERSES);

        exercice = new ExerciceComptable();
        exercice.setId(UUID.randomUUID());
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(2026, 1, 1));
        exercice.setDateFin(LocalDate.of(2026, 12, 31));
        exercice.setStatut(StatutExercice.OUVERT);

        when(articleRepository.findById(article.getId())).thenReturn(Optional.of(article));
    }

    private CompteComptable compte(String numero, String intitule) {
        CompteComptable compte = new CompteComptable();
        compte.setId(UUID.randomUUID());
        compte.setEntreprise(entreprise);
        compte.setNumero(numero);
        compte.setIntitule(intitule);
        compte.setLettrable(false);
        return compte;
    }

    /** Le contexte comptable (exercice, journal, comptes) est resolu par le moteur : il suffit de simuler sa reponse. */
    private void mockerContexteComptable() {
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateurCourant);
        when(mouvementStockRepository.save(any())).thenAnswer(inv -> {
            MouvementStock mouvement = inv.getArgument(0);
            if (mouvement.getId() == null) {
                mouvement.setId(UUID.randomUUID());
            }
            return mouvement;
        });
        lenient().when(stockMouvementRule.construire(any(), any(), any(), anyBoolean(), any(), anyString()))
                .thenReturn(new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, List.of(), List.of(), List.of()));
        AccountingOperationProposalDto proposition = org.mockito.Mockito.mock(AccountingOperationProposalDto.class);
        lenient().when(proposition.ecritureId()).thenReturn(UUID.randomUUID());
        lenient().when(accountingOperationEngine.proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(proposition);
    }

    @Test
    void premiere_entree_fixe_le_pump_au_prix_dachat() {
        mockerContexteComptable();

        MouvementStockDto resultat = mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.ENTREE,
                        new BigDecimal("10"), new BigDecimal("1000"), LocalDate.of(2026, 3, 1), "Achat 1"),
                principal);

        assertThat(article.getQuantiteEnStock()).isEqualByComparingTo("10");
        assertThat(article.getValeurStockTotale()).isEqualByComparingTo("10000");
        assertThat(article.getPump()).isEqualByComparingTo("1000");
        assertThat(resultat.quantiteApres()).isEqualByComparingTo("10");
        assertThat(resultat.pumpApres()).isEqualByComparingTo("1000");
        assertThat(resultat.ecritureId()).isNotNull();
    }

    @Test
    void deuxieme_entree_recalcule_le_pump_pondere() {
        mockerContexteComptable();
        article.setQuantiteEnStock(new BigDecimal("10"));
        article.setValeurStockTotale(new BigDecimal("10000"));
        article.setPump(new BigDecimal("1000"));

        MouvementStockDto resultat = mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.ENTREE,
                        new BigDecimal("10"), new BigDecimal("1400"), LocalDate.of(2026, 3, 5), "Achat 2"),
                principal);

        // (10000 + 10*1400) / (10+10) = 24000 / 20 = 1200
        assertThat(article.getQuantiteEnStock()).isEqualByComparingTo("20");
        assertThat(article.getValeurStockTotale()).isEqualByComparingTo("24000");
        assertThat(article.getPump()).isEqualByComparingTo("1200");
        assertThat(resultat.pumpApres()).isEqualByComparingTo("1200");
    }

    @Test
    void la_sortie_destocke_au_pump_en_vigueur_sans_le_modifier() {
        mockerContexteComptable();
        article.setQuantiteEnStock(new BigDecimal("20"));
        article.setValeurStockTotale(new BigDecimal("24000"));
        article.setPump(new BigDecimal("1200"));

        MouvementStockDto resultat = mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.SORTIE,
                        new BigDecimal("5"), null, LocalDate.of(2026, 3, 10), "Vente 1"),
                principal);

        assertThat(article.getQuantiteEnStock()).isEqualByComparingTo("15");
        assertThat(article.getValeurStockTotale()).isEqualByComparingTo("18000");
        assertThat(article.getPump()).isEqualByComparingTo("1200");
        assertThat(resultat.valeurMouvement()).isEqualByComparingTo("6000");
        assertThat(resultat.prixUnitaireHt()).isEqualByComparingTo("1200");
    }

    @Test
    void refuse_une_sortie_superieure_au_stock_disponible() {
        article.setQuantiteEnStock(new BigDecimal("5"));
        article.setValeurStockTotale(new BigDecimal("6000"));
        article.setPump(new BigDecimal("1200"));

        assertThatThrownBy(() -> mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.SORTIE,
                        new BigDecimal("10"), null, LocalDate.of(2026, 3, 10), "Vente trop grosse"),
                principal))
                .hasMessageContaining("Stock insuffisant");
    }

    @Test
    void refuse_une_entree_sans_prix_unitaire() {
        assertThatThrownBy(() -> mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.ENTREE,
                        new BigDecimal("10"), null, LocalDate.of(2026, 3, 1), "Achat sans prix"),
                principal))
                .hasMessageContaining("prix unitaire");
    }

    @Test
    void un_ajustement_positif_valorise_le_surplus_au_pump_courant_sans_le_modifier() {
        mockerContexteComptable();
        article.setQuantiteEnStock(new BigDecimal("15"));
        article.setValeurStockTotale(new BigDecimal("18000"));
        article.setPump(new BigDecimal("1200"));

        MouvementStockDto resultat = mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.AJUSTEMENT,
                        new BigDecimal("2"), null, LocalDate.of(2026, 3, 12), "Surplus inventaire"),
                principal);

        assertThat(article.getQuantiteEnStock()).isEqualByComparingTo("17");
        assertThat(article.getValeurStockTotale()).isEqualByComparingTo("20400");
        assertThat(article.getPump()).isEqualByComparingTo("1200");
        assertThat(resultat.quantite()).isEqualByComparingTo("2");
    }

    @Test
    void un_ajustement_negatif_constate_une_perte_au_pump_courant() {
        mockerContexteComptable();
        article.setQuantiteEnStock(new BigDecimal("15"));
        article.setValeurStockTotale(new BigDecimal("18000"));
        article.setPump(new BigDecimal("1200"));

        MouvementStockDto resultat = mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.AJUSTEMENT,
                        new BigDecimal("-3"), null, LocalDate.of(2026, 3, 12), "Casse constatee"),
                principal);

        assertThat(article.getQuantiteEnStock()).isEqualByComparingTo("12");
        assertThat(article.getValeurStockTotale()).isEqualByComparingTo("14400");
        assertThat(article.getPump()).isEqualByComparingTo("1200");
        assertThat(resultat.quantite()).isEqualByComparingTo("-3");
    }

    @Test
    void refuse_un_ajustement_nul() {
        assertThatThrownBy(() -> mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.AJUSTEMENT,
                        BigDecimal.ZERO, null, LocalDate.of(2026, 3, 12), "Rien"),
                principal))
                .hasMessageContaining("ecart");
    }

    @Test
    void un_refus_du_moteur_comptable_remonte_et_annule_le_mouvement() {
        mockerContexteComptable();
        when(accountingOperationEngine.proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucun exercice comptable ne couvre la date 2026-03-01"));

        assertThatThrownBy(() -> mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.ENTREE,
                        new BigDecimal("10"), new BigDecimal("1000"), LocalDate.of(2026, 3, 1), "Achat"),
                principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(422);
                    assertThat(e.getReason()).contains("Aucun exercice");
                });
    }

    @Test
    void une_entree_est_confiee_au_moteur_comme_STOCK_ENTREE_avec_le_mouvement_comme_source() {
        mockerContexteComptable();
        MouvementStockDto resultat = mouvementStockService.enregistrer(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.ENTREE,
                        new BigDecimal("10"), new BigDecimal("1000"), LocalDate.of(2026, 3, 1), "Achat 1"),
                principal);

        verify(stockMouvementRule).construire(eq(entreprise), eq(article), eq(AccountingOperationType.STOCK_ENTREE),
                eq(true), argThat(v -> v.compareTo(new BigDecimal("10000")) == 0), anyString());
        verify(accountingOperationEngine).proposerEtValider(eq(entrepriseId), eq(AccountingOperationType.STOCK_ENTREE), eq(null), eq(LocalDate.of(2026, 3, 1)), anyString(), eq(null), any(), argThat(src -> src.type().equals(MouvementStockService.SOURCE_TYPE_MOUVEMENT) && src.event().equals("ENTREE")), eq(StatutEcriture.VALIDEE), eq(principal));
        assertThat(resultat.ecritureId()).isNotNull();
    }

    @Test
    void une_sortie_est_STOCK_SORTIE_a_la_valeur_CUMP_et_un_ajustement_negatif_est_STOCK_AJUSTEMENT_en_sortie() {
        mockerContexteComptable();
        article.setQuantiteEnStock(new BigDecimal("20"));
        article.setValeurStockTotale(new BigDecimal("24000"));
        article.setPump(new BigDecimal("1200"));

        mouvementStockService.enregistrer(entrepriseId, new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.SORTIE,
                new BigDecimal("5"), null, LocalDate.of(2026, 3, 10), "Vente"), principal);
        verify(stockMouvementRule).construire(eq(entreprise), eq(article), eq(AccountingOperationType.STOCK_SORTIE),
                eq(false), argThat(v -> v.compareTo(new BigDecimal("6000")) == 0), anyString());

        mouvementStockService.enregistrer(entrepriseId, new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.AJUSTEMENT,
                new BigDecimal("-2"), null, LocalDate.of(2026, 3, 11), "Casse"), principal);
        verify(stockMouvementRule).construire(eq(entreprise), eq(article), eq(AccountingOperationType.STOCK_AJUSTEMENT),
                eq(false), any(), anyString());
    }

    @Test
    void un_mouvement_de_valeur_nulle_ne_produit_aucune_commande_comptable() {
        mockerContexteComptable();
        mouvementStockService.enregistrer(entrepriseId, new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.ENTREE,
                new BigDecimal("10"), BigDecimal.ZERO, LocalDate.of(2026, 3, 1), "Echantillon gratuit"), principal);

        verify(accountingOperationEngine, never()).proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any());
        verify(mouvementStockRepository).save(any());
    }

    @Test
    void mouvement_automatique_sur_article_intermittent_ne_genere_rien() {
        article.setModeGestionStock(com.example.comptable.stock.domain.ModeGestionStock.INTERMITTENT);

        MouvementStockDto resultat = mouvementStockService.enregistrerAvecSource(entrepriseId,
                new CreerMouvementStockRequest(article.getId(), TypeMouvementStock.ENTREE_ACHAT,
                        new BigDecimal("10"), new BigDecimal("1000"), LocalDate.of(2026, 3, 1), "Reception"),
                principal, "COMMANDE_ACHAT", UUID.randomUUID());

        assertThat(resultat).isNull();
        verify(mouvementStockRepository, never()).save(any());
        verify(accountingOperationEngine, never()).proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any());
    }
}
