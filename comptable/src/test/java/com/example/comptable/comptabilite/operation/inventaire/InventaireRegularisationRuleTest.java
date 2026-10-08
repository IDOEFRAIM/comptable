package com.example.comptable.comptabilite.operation.inventaire;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.inventaire.domain.Inventaire;
import com.example.comptable.stock.inventaire.domain.LigneInventaire;
import com.example.comptable.stock.inventaire.domain.StatutInventaire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventaireRegularisationRuleTest {

    @Mock
    private com.example.comptable.comptabilite.parametrage.AccountingAccountResolver accountingAccountResolver;

    private InventaireRegularisationRule rule;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private CompteComptable compteStock;
    private CompteComptable compteVariation;
    private Article article;

    @BeforeEach
    void setUp() {
        rule = new InventaireRegularisationRule(new com.example.comptable.comptabilite.operation.stock.StockAccountResolver(accountingAccountResolver));
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        compteStock = compte("311");
        compteVariation = compte("6031");

        article = new Article();
        article.setId(UUID.randomUUID());
        article.setEntreprise(entreprise);
        article.setCodeArticle("ART-001");
        article.setDesignation("Sac de ciment");
        article.setCompteStock(compteStock);
        article.setCompteVariation(compteVariation);
    }

    private void stubStock(CompteComptable compteStock) {
        when(accountingAccountResolver.resoudreCompte(eq(entrepriseId),
                eq(com.example.comptable.comptabilite.parametrage.domain.AccountPurpose.STOCK_MARCHANDISE), any(), eq(compteStock.getId())))
                .thenReturn(new com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu(compteStock, null));
    }

    private void stubVariation(CompteComptable compteVariation) {
        when(accountingAccountResolver.resoudreCompte(eq(entrepriseId),
                eq(com.example.comptable.comptabilite.parametrage.domain.AccountPurpose.VARIATION_STOCK), any(), eq(compteVariation.getId())))
                .thenReturn(new com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu(compteVariation, null));
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        c.setIntitule(numero);
        c.setActif(true);
        return c;
    }

    private Inventaire inventaireAvec(LigneInventaire... lignes) {
        Inventaire inventaire = new Inventaire();
        inventaire.setId(UUID.randomUUID());
        inventaire.setEntreprise(entreprise);
        inventaire.setDate(LocalDate.now());
        inventaire.setStatut(StatutInventaire.EN_COURS);
        for (LigneInventaire ligne : lignes) {
            inventaire.ajouterLigne(ligne);
        }
        return inventaire;
    }

    private LigneInventaire ligne(Article article, BigDecimal ecart, BigDecimal valeurEcart) {
        LigneInventaire ligne = new LigneInventaire();
        ligne.setArticle(article);
        ligne.setEcart(ecart);
        ligne.setValeurEcart(valeurEcart);
        return ligne;
    }

    @Test
    void surplus_debite_le_compte_de_stock_et_credite_la_variation() {
        stubStock(compteStock);
        stubVariation(compteVariation);

        ResultatRegle resultat = rule.construire(entreprise, inventaireAvec(ligne(article, new BigDecimal("5"), new BigDecimal("600"))));

        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.OPERATIONS_DIVERSES);
        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).hasSize(2);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("311");
            assertThat(l.getDebit()).isEqualByComparingTo("600");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("6031");
            assertThat(l.getCredit()).isEqualByComparingTo("600");
        });
        BigDecimal totalDebit = resultat.lignes().stream().map(AccountingOperationProposalLigne::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = resultat.lignes().stream().map(AccountingOperationProposalLigne::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
    }

    @Test
    void perte_debite_la_variation_et_credite_le_compte_de_stock() {
        stubStock(compteStock);
        stubVariation(compteVariation);

        ResultatRegle resultat = rule.construire(entreprise, inventaireAvec(ligne(article, new BigDecimal("-2"), new BigDecimal("240"))));

        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("6031");
            assertThat(l.getDebit()).isEqualByComparingTo("240");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("311");
            assertThat(l.getCredit()).isEqualByComparingTo("240");
        });
    }

    @Test
    void ligne_sans_ecart_est_ignoree() {
        ResultatRegle resultat = rule.construire(entreprise, inventaireAvec(ligne(article, BigDecimal.ZERO, BigDecimal.ZERO)));

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).isEmpty();
    }

    @Test
    void compte_inactif_produit_une_explication_plutot_quune_ligne_incorrecte() {
        when(accountingAccountResolver.resoudreCompte(eq(entrepriseId),
                eq(com.example.comptable.comptabilite.parametrage.domain.AccountPurpose.STOCK_MARCHANDISE), any(), eq(compteStock.getId())))
                .thenReturn(new com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu(null,
                        com.example.comptable.comptabilite.parametrage.ResolvedAccount.invalide("Compte configure indisponible : Le compte 311 est desactive")));
        when(accountingAccountResolver.resoudreCompte(eq(entrepriseId),
                eq(com.example.comptable.comptabilite.parametrage.domain.AccountPurpose.VARIATION_STOCK), any(), eq(compteVariation.getId())))
                .thenReturn(new com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu(compteVariation, null));

        ResultatRegle resultat = rule.construire(entreprise, inventaireAvec(ligne(article, new BigDecimal("5"), new BigDecimal("600"))));

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).anySatisfy(e -> assertThat(e).contains("ART-001").contains("INVALID").contains("311"));
    }
}
