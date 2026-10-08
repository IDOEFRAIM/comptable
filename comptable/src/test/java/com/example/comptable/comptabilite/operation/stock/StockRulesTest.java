package com.example.comptable.comptabilite.operation.stock;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.TypeArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Phase A : comptes de stock demandes par purpose au resolveur central ; jamais un numero, jamais un compte devine. */
@ExtendWith(MockitoExtension.class)
class StockRulesTest {

    @Mock
    private AccountingAccountResolver accountingAccountResolver;

    private StockMouvementRule rule;
    private Entreprise entreprise;
    private Article article;
    private CompteComptable compteStock;
    private CompteComptable compteVariation;

    @BeforeEach
    void setUp() {
        rule = new StockMouvementRule(new StockAccountResolver(accountingAccountResolver));
        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());
        entreprise.setPays("CI");
        compteStock = compte("Stock");
        compteVariation = compte("Variation");
        article = new Article();
        article.setId(UUID.randomUUID());
        article.setEntreprise(entreprise);
        article.setCodeArticle("ART-1");
        article.setDesignation("Ciment");
        article.setType(TypeArticle.MARCHANDISE);
        article.setCompteStock(compteStock);
        article.setCompteVariation(compteVariation);
    }

    private CompteComptable compte(String intitule) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setNumero("N" + intitule);
        c.setIntitule(intitule);
        return c;
    }

    private void stock(AccountPurpose purpose, CompteResolu resultat) {
        when(accountingAccountResolver.resoudreCompte(eq(entreprise.getId()), eq(purpose), any(), eq(compteStock.getId()))).thenReturn(resultat);
    }

    private void variation(CompteResolu resultat) {
        when(accountingAccountResolver.resoudreCompte(eq(entreprise.getId()), eq(AccountPurpose.VARIATION_STOCK), any(), eq(compteVariation.getId())))
                .thenReturn(resultat);
    }

    @Test
    void le_purpose_de_stock_depend_de_la_nature_de_l_article_jamais_du_libelle() {
        assertThat(StockAccountResolver.purposeStock(TypeArticle.MARCHANDISE)).isEqualTo(AccountPurpose.STOCK_MARCHANDISE);
        assertThat(StockAccountResolver.purposeStock(TypeArticle.MATIERE_PREMIERE)).isEqualTo(AccountPurpose.STOCK_MATIERE);
        assertThat(StockAccountResolver.purposeStock(TypeArticle.PRODUIT_FINI)).isEqualTo(AccountPurpose.STOCK_PRODUIT);
        assertThat(StockAccountResolver.purposeStock(TypeArticle.PRODUIT_INTERMEDIAIRE)).isEqualTo(AccountPurpose.STOCK_PRODUIT);
        assertThat(StockAccountResolver.purposeStock(TypeArticle.AUTRE)).isEqualTo(AccountPurpose.STOCK_AUTRE);
        for (TypeArticle type : TypeArticle.values()) {
            assertThat(StockAccountResolver.purposeStock(type).classesAttendues()).containsExactly(3);
        }
    }

    @Test
    void entree_debite_le_stock_et_credite_la_variation() {
        stock(AccountPurpose.STOCK_MARCHANDISE, new CompteResolu(compteStock, null));
        variation(new CompteResolu(compteVariation, null));

        ResultatRegle resultat = rule.construire(entreprise, article, AccountingOperationType.STOCK_ENTREE, true, new BigDecimal("10000"), "Entree");

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.OPERATIONS_DIVERSES);
        assertThat(resultat.lignes()).hasSize(2);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isSameAs(compteStock);
            assertThat(l.getDebit()).isEqualByComparingTo("10000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isSameAs(compteVariation);
            assertThat(l.getCredit()).isEqualByComparingTo("10000");
        });
    }

    @Test
    void sortie_debite_la_variation_et_credite_le_stock() {
        stock(AccountPurpose.STOCK_MARCHANDISE, new CompteResolu(compteStock, null));
        variation(new CompteResolu(compteVariation, null));

        ResultatRegle resultat = rule.construire(entreprise, article, AccountingOperationType.STOCK_SORTIE, false, new BigDecimal("6000"), "Sortie");

        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isSameAs(compteVariation);
            assertThat(l.getDebit()).isEqualByComparingTo("6000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isSameAs(compteStock);
            assertThat(l.getCredit()).isEqualByComparingTo("6000");
        });
    }

    @Test
    void compte_non_configure_ou_invalide_donne_des_explications_et_aucune_ligne() {
        stock(AccountPurpose.STOCK_MARCHANDISE, new CompteResolu(null, ResolvedAccount.nonConfigure("Aucun mapping STOCK_MARCHANDISE")));
        variation(new CompteResolu(null, ResolvedAccount.invalide("Compte configure indisponible : desactive")));

        ResultatRegle resultat = rule.construire(entreprise, article, AccountingOperationType.STOCK_ENTREE, true, new BigDecimal("10000"), "Entree");

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).hasSize(2);
        assertThat(resultat.explications().get(0)).contains("ART-1").contains("NOT_CONFIGURED");
        assertThat(resultat.explications().get(1)).contains("variation de stock").contains("INVALID");
    }

    @Test
    void une_matiere_premiere_demande_le_purpose_STOCK_MATIERE() {
        article.setType(TypeArticle.MATIERE_PREMIERE);
        stock(AccountPurpose.STOCK_MATIERE, new CompteResolu(compteStock, null));
        variation(new CompteResolu(compteVariation, null));

        ResultatRegle resultat = rule.construire(entreprise, article, AccountingOperationType.STOCK_ENTREE, true, BigDecimal.TEN, "Entree");

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).hasSize(2);
    }
}
