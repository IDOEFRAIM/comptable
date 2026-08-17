package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.web.dto.ArticleDto;
import com.example.comptable.stock.web.dto.CreerArticleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private ArticleRepository articleRepository;
    @Mock
    private CompteComptableRepository compteComptableRepository;

    @InjectMocks
    private ArticleService articleService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private CompteComptable compteStock;
    private CompteComptable compteAchat;
    private CompteComptable compteVente;
    private CompteComptable compteVariation;

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

        compteStock = compte("311", "Marchandises");
        compteAchat = compte("601", "Achats de marchandises");
        compteVente = compte("701", "Ventes de marchandises");
        compteVariation = compte("6031", "Variation de stocks de marchandises");

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
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

    private CreerArticleRequest requeteValide() {
        return new CreerArticleRequest("ART-001", "Sac de ciment 50kg", "Materiaux", "unite",
                new BigDecimal("2000"), new BigDecimal("18"),
                compteStock.getId(), compteAchat.getId(), compteVente.getId(), compteVariation.getId());
    }

    @Test
    void cree_un_article_avec_stock_initial_nul() {
        when(articleRepository.findByEntrepriseIdAndCodeArticle(entrepriseId, "ART-001")).thenReturn(Optional.empty());
        when(compteComptableRepository.findById(compteStock.getId())).thenReturn(Optional.of(compteStock));
        when(compteComptableRepository.findById(compteAchat.getId())).thenReturn(Optional.of(compteAchat));
        when(compteComptableRepository.findById(compteVente.getId())).thenReturn(Optional.of(compteVente));
        when(compteComptableRepository.findById(compteVariation.getId())).thenReturn(Optional.of(compteVariation));
        when(articleRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            Article a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        ArticleDto resultat = articleService.creer(entrepriseId, requeteValide(), principal);

        assertThat(resultat.codeArticle()).isEqualTo("ART-001");
        assertThat(resultat.quantiteEnStock()).isEqualByComparingTo("0");
        assertThat(resultat.pump()).isEqualByComparingTo("0");
        assertThat(resultat.compteStockNumero()).isEqualTo("311");
        assertThat(resultat.compteVariationNumero()).isEqualTo("6031");
    }

    @Test
    void refuse_un_code_article_deja_utilise() {
        Article existant = new Article();
        existant.setId(UUID.randomUUID());
        when(articleRepository.findByEntrepriseIdAndCodeArticle(entrepriseId, "ART-001")).thenReturn(Optional.of(existant));

        assertThatThrownBy(() -> articleService.creer(entrepriseId, requeteValide(), principal))
                .hasMessageContaining("existe deja");
    }

    @Test
    void refuse_un_compte_dune_autre_entreprise() {
        when(articleRepository.findByEntrepriseIdAndCodeArticle(entrepriseId, "ART-001")).thenReturn(Optional.empty());
        CompteComptable compteAutreEntreprise = new CompteComptable();
        compteAutreEntreprise.setId(compteStock.getId());
        Entreprise autre = new Entreprise();
        autre.setId(UUID.randomUUID());
        compteAutreEntreprise.setEntreprise(autre);
        when(compteComptableRepository.findById(compteStock.getId())).thenReturn(Optional.of(compteAutreEntreprise));

        assertThatThrownBy(() -> articleService.creer(entrepriseId, requeteValide(), principal))
                .hasMessageContaining("introuvable");
    }
}
