package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FacturationServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private FactureRepository factureRepository;
    @Mock
    private TiersRepository tiersRepository;
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
    @Mock
    private MouvementStockService mouvementStockService;

    @InjectMocks
    private FacturationService facturationService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Utilisateur utilisateur;
    private Tiers client;
    private CompteComptable compteClient;
    private CompteComptable compteVentes;
    private CompteComptable compteTva;
    private Journal journalVte;
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

        journalVte = new Journal();
        journalVte.setId(UUID.randomUUID());
        journalVte.setEntreprise(entreprise);
        journalVte.setCode("VTE");

        exercice = new ExerciceComptable();
        exercice.setId(UUID.randomUUID());
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(2027, 1, 1));
        exercice.setDateFin(LocalDate.of(2027, 12, 31));
        exercice.setStatut(StatutExercice.OUVERT);

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

        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(compteComptableRepository.findById(compteVentes.getId())).thenReturn(Optional.of(compteVentes));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, facture.getDateEmission()))
                .thenReturn(Optional.of(exercice));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "VTE")).thenReturn(Optional.of(journalVte));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(ecritureComptableRepository.countByJournalId(journalVte.getId())).thenReturn(0L);
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "443")).thenReturn(Optional.of(compteTva));
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.validerFacture(entrepriseId, facture.getId(), compteVentes.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("VALIDEE");
        assertThat(resultat.ecritureId()).isNotNull();
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
    void marque_payee_une_facture_validee() {
        Facture fact = facture(TypeFacture.FACTURE, StatutFacture.VALIDEE);
        when(factureRepository.findById(fact.getId())).thenReturn(Optional.of(fact));
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.marquerPayee(entrepriseId, fact.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("PAYEE");
    }

    @Test
    void refuse_de_marquer_payee_une_facture_en_brouillon() {
        Facture fact = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);
        when(factureRepository.findById(fact.getId())).thenReturn(Optional.of(fact));

        assertThatThrownBy(() -> facturationService.marquerPayee(entrepriseId, fact.getId(), principal))
                .hasMessageContaining("validee");
    }

    @Test
    void refuse_de_valider_une_facture_dont_lexercice_est_cloture() {
        Facture fact = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);
        exercice.setStatut(StatutExercice.CLOTURE);

        when(factureRepository.findById(fact.getId())).thenReturn(Optional.of(fact));
        when(compteComptableRepository.findById(compteVentes.getId())).thenReturn(Optional.of(compteVentes));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, fact.getDateEmission()))
                .thenReturn(Optional.of(exercice));

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

        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(compteComptableRepository.findById(compteVentes.getId())).thenReturn(Optional.of(compteVentes));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, facture.getDateEmission()))
                .thenReturn(Optional.of(exercice));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "VTE")).thenReturn(Optional.of(journalVte));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(ecritureComptableRepository.countByJournalId(journalVte.getId())).thenReturn(0L);
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "443")).thenReturn(Optional.of(compteTva));
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(mouvementStockService.enregistrer(eq(entrepriseId), any(), eq(principal))).thenReturn(new MouvementStockDto(
                mouvementId, articleId, "ART-001", "SORTIE", new BigDecimal("10"), new BigDecimal("1200"),
                new BigDecimal("12000"), new BigDecimal("5"), new BigDecimal("1200"), facture.getDateEmission(),
                "Vente FAC-2027-0001", UUID.randomUUID()));
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.validerFacture(entrepriseId, facture.getId(), compteVentes.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("VALIDEE");
        assertThat(resultat.lignes().get(0).mouvementStockId()).isEqualTo(mouvementId);

        ArgumentCaptor<CreerMouvementStockRequest> captor = ArgumentCaptor.forClass(CreerMouvementStockRequest.class);
        verify(mouvementStockService, times(1)).enregistrer(eq(entrepriseId), captor.capture(), eq(principal));
        assertThat(captor.getValue().articleId()).isEqualTo(articleId);
        assertThat(captor.getValue().type()).isEqualTo(TypeMouvementStock.SORTIE);
        assertThat(captor.getValue().quantite()).isEqualByComparingTo("10");
    }

    @Test
    void propage_lexception_stock_insuffisant_lors_de_la_validation() {
        Facture facture = facture(TypeFacture.FACTURE, StatutFacture.BROUILLON);
        facture.getLignes().get(0).setArticleId(UUID.randomUUID());

        when(factureRepository.findById(facture.getId())).thenReturn(Optional.of(facture));
        when(compteComptableRepository.findById(compteVentes.getId())).thenReturn(Optional.of(compteVentes));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, facture.getDateEmission()))
                .thenReturn(Optional.of(exercice));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "VTE")).thenReturn(Optional.of(journalVte));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(ecritureComptableRepository.countByJournalId(journalVte.getId())).thenReturn(0L);
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "443")).thenReturn(Optional.of(compteTva));
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(mouvementStockService.enregistrer(eq(entrepriseId), any(), eq(principal)))
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
        when(mouvementStockService.enregistrer(eq(entrepriseId), any(), eq(principal))).thenReturn(mock(MouvementStockDto.class));
        when(factureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FactureDto resultat = facturationService.annulerFacture(entrepriseId, facture.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("ANNULEE");

        ArgumentCaptor<CreerMouvementStockRequest> captor = ArgumentCaptor.forClass(CreerMouvementStockRequest.class);
        verify(mouvementStockService, times(1)).enregistrer(eq(entrepriseId), captor.capture(), eq(principal));
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
}
