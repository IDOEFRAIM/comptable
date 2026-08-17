package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.CommandeAchat;
import com.example.comptable.stock.domain.StatutCommandeAchat;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.repository.CommandeAchatRepository;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import com.example.comptable.stock.web.dto.CommandeAchatDto;
import com.example.comptable.stock.web.dto.CreerCommandeAchatRequest;
import com.example.comptable.stock.web.dto.FactureFournisseurDto;
import com.example.comptable.stock.web.dto.LigneCommandeAchatRequest;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandeAchatServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private CommandeAchatRepository commandeAchatRepository;
    @Mock
    private TiersRepository tiersRepository;
    @Mock
    private ArticleRepository articleRepository;
    @Mock
    private MouvementStockService mouvementStockService;
    @Mock
    private FactureFournisseurRepository factureFournisseurRepository;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private ExerciceComptableRepository exerciceComptableRepository;
    @Mock
    private JournalRepository journalRepository;
    @Mock
    private EcritureComptableRepository ecritureComptableRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;

    @InjectMocks
    private CommandeAchatService commandeAchatService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Utilisateur utilisateur;
    private Tiers fournisseur;
    private Article article;
    private CompteComptable compteAchat;
    private CompteComptable compteTva;
    private CompteComptable compteFournisseur;
    private Journal journalAch;
    private ExerciceComptable exercice;

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

        compteTva = new CompteComptable();
        compteTva.setId(UUID.randomUUID());
        compteTva.setEntreprise(entreprise);
        compteTva.setNumero("4456");

        journalAch = new Journal();
        journalAch.setId(UUID.randomUUID());
        journalAch.setEntreprise(entreprise);
        journalAch.setCode("ACH");

        exercice = new ExerciceComptable();
        exercice.setId(UUID.randomUUID());
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(2026, 1, 1));
        exercice.setDateFin(LocalDate.of(2026, 12, 31));
        exercice.setStatut(StatutExercice.OUVERT);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private CreerCommandeAchatRequest requete() {
        return new CreerCommandeAchatRequest(fournisseur.getId(), LocalDate.of(2026, 3, 1),
                List.of(new LigneCommandeAchatRequest(article.getId(), new BigDecimal("10"), new BigDecimal("1000"))));
    }

    @Test
    void cree_une_commande_avec_un_numero_sequentiel() {
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(articleRepository.findById(article.getId())).thenReturn(Optional.of(article));
        when(commandeAchatRepository.countByEntrepriseId(entrepriseId)).thenReturn(3L);
        when(commandeAchatRepository.save(any())).thenAnswer(inv -> {
            CommandeAchat c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CommandeAchatDto resultat = commandeAchatService.creer(entrepriseId, requete(), principal);

        assertThat(resultat.numero()).isEqualTo("BC-2026-0004");
        assertThat(resultat.statut()).isEqualTo("EN_COURS");
        assertThat(resultat.lignes()).hasSize(1);
        assertThat(resultat.montantTotalHt()).isEqualByComparingTo("10000");
    }

    @Test
    void refuse_un_tiers_qui_nest_pas_un_fournisseur() {
        Tiers client = new Tiers();
        client.setId(UUID.randomUUID());
        client.setEntreprise(entreprise);
        client.setType(TypeTiers.CLIENT);
        client.setNom("Client Alpha");
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> commandeAchatService.creer(entrepriseId, requete(), principal))
                .hasMessageContaining("fournisseur");
    }

    @Test
    void la_reception_declenche_une_entree_en_stock_par_ligne_et_marque_la_commande_receptionnee() {
        CommandeAchat commande = commandeCreee();
        when(commandeAchatRepository.findById(commande.getId())).thenReturn(Optional.of(commande));
        when(mouvementStockService.enregistrer(any(), any(), any())).thenReturn(mock(MouvementStockDto.class));
        when(commandeAchatRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CommandeAchatDto resultat = commandeAchatService.validerReception(entrepriseId, commande.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("RECEPTIONNEE");
        assertThat(resultat.dateReception()).isEqualTo(LocalDate.now());

        ArgumentCaptor<com.example.comptable.stock.web.dto.CreerMouvementStockRequest> captor =
                ArgumentCaptor.forClass(com.example.comptable.stock.web.dto.CreerMouvementStockRequest.class);
        verify(mouvementStockService, times(1)).enregistrer(org.mockito.ArgumentMatchers.eq(entrepriseId), captor.capture(), org.mockito.ArgumentMatchers.eq(principal));
        assertThat(captor.getValue().type()).isEqualTo(com.example.comptable.stock.domain.TypeMouvementStock.ENTREE);
        assertThat(captor.getValue().quantite()).isEqualByComparingTo("10");
        assertThat(captor.getValue().prixUnitaireHt()).isEqualByComparingTo("1000");
    }

    @Test
    void refuse_de_receptionner_deux_fois_la_meme_commande() {
        CommandeAchat commande = commandeCreee();
        commande.setStatut(StatutCommandeAchat.RECEPTIONNEE);
        when(commandeAchatRepository.findById(commande.getId())).thenReturn(Optional.of(commande));

        assertThatThrownBy(() -> commandeAchatService.validerReception(entrepriseId, commande.getId(), principal))
                .hasMessageContaining("deja");
        verify(mouvementStockService, never()).enregistrer(any(), any(), any());
    }

    @Test
    void genere_une_facture_fournisseur_avec_lecriture_dachat_601_4456_401() {
        CommandeAchat commande = commandeCreee();
        commande.setStatut(StatutCommandeAchat.RECEPTIONNEE);

        when(commandeAchatRepository.findById(commande.getId())).thenReturn(Optional.of(commande));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(any(), any())).thenReturn(Optional.of(exercice));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "ACH")).thenReturn(Optional.of(journalAch));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(ecritureComptableRepository.countByJournalId(journalAch.getId())).thenReturn(0L);
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "4456")).thenReturn(Optional.of(compteTva));
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(factureFournisseurRepository.countByEntrepriseId(entrepriseId)).thenReturn(0L);
        when(factureFournisseurRepository.save(any())).thenAnswer(inv -> {
            com.example.comptable.stock.domain.FactureFournisseur f = inv.getArgument(0);
            f.setId(UUID.randomUUID());
            return f;
        });
        when(commandeAchatRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureFournisseurDto resultat = commandeAchatService.genererFactureFournisseur(entrepriseId, commande.getId(), principal);

        // 10 x 1000 = 10000 HT ; TVA 18% = 1800 ; TTC = 11800
        assertThat(resultat.numero()).isEqualTo("FF-2026-0001");
        assertThat(resultat.montantHt()).isEqualByComparingTo("10000");
        assertThat(resultat.montantTva()).isEqualByComparingTo("1800");
        assertThat(resultat.montantTtc()).isEqualByComparingTo("11800");
        assertThat(resultat.ecritureId()).isNotNull();
        assertThat(commande.getStatut()).isEqualTo(StatutCommandeAchat.FACTUREE);

        ArgumentCaptor<EcritureComptable> captor = ArgumentCaptor.forClass(EcritureComptable.class);
        verify(ecritureComptableRepository).save(captor.capture());
        EcritureComptable ecriture = captor.getValue();
        assertThat(ecriture.estEquilibree()).isTrue();
        assertThat(ecriture.getLignes()).hasSize(3);
        assertThat(ecriture.getLignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("601");
            assertThat(l.getDebit()).isEqualByComparingTo("10000");
        });
        assertThat(ecriture.getLignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("4456");
            assertThat(l.getDebit()).isEqualByComparingTo("1800");
        });
        assertThat(ecriture.getLignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("401001");
            assertThat(l.getCredit()).isEqualByComparingTo("11800");
        });
    }

    @Test
    void refuse_de_facturer_une_commande_non_receptionnee() {
        CommandeAchat commande = commandeCreee();

        when(commandeAchatRepository.findById(commande.getId())).thenReturn(Optional.of(commande));

        assertThatThrownBy(() -> commandeAchatService.genererFactureFournisseur(entrepriseId, commande.getId(), principal))
                .hasMessageContaining("pas encore ete receptionnee");
    }

    @Test
    void refuse_de_facturer_deux_fois_la_meme_commande() {
        CommandeAchat commande = commandeCreee();
        commande.setStatut(StatutCommandeAchat.FACTUREE);

        when(commandeAchatRepository.findById(commande.getId())).thenReturn(Optional.of(commande));

        assertThatThrownBy(() -> commandeAchatService.genererFactureFournisseur(entrepriseId, commande.getId(), principal))
                .hasMessageContaining("deja ete facturee");
    }

    private CommandeAchat commandeCreee() {
        CommandeAchat commande = new CommandeAchat();
        commande.setId(UUID.randomUUID());
        commande.setEntreprise(entreprise);
        commande.setFournisseur(fournisseur);
        commande.setNumero("BC-2026-0001");
        commande.setDateCommande(LocalDate.of(2026, 3, 1));
        commande.setStatut(StatutCommandeAchat.EN_COURS);

        com.example.comptable.stock.domain.LigneCommandeAchat ligne = new com.example.comptable.stock.domain.LigneCommandeAchat();
        ligne.setArticle(article);
        ligne.setQuantite(new BigDecimal("10"));
        ligne.setPrixUnitaireHt(new BigDecimal("1000"));
        commande.ajouterLigne(ligne);
        return commande;
    }
}
