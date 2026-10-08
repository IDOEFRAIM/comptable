package com.example.comptable.comptabilite.operation.stock;

import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.TypeArticle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Seul endroit ou une regle comptable de stock demande « quel compte pour cet article ? » (Phase A).
 * Le module Stock decide des quantites et des valeurs ; ce composant ne fait que traduire l'article en
 * {@link AccountPurpose} (selon son {@link TypeArticle}) et deleguer a {@link AccountingAccountResolver}.
 * <p>
 * Les comptes choisis explicitement sur l'article (stock, variation) sont passes comme compte
 * explicite : c'est le premier palier du resolveur, revalide (actif, de l'entreprise, utilisable en
 * detail). Un compte desactive donne INVALID, jamais une imputation silencieuse ailleurs ; sans compte
 * explicite, le mapping de l'entreprise pour le purpose s'applique, et sinon NOT_CONFIGURED.
 */
@Component
@RequiredArgsConstructor
public class StockAccountResolver {

    private final AccountingAccountResolver accountingAccountResolver;

    /** Purpose du compte de stock selon la nature de l'article (jamais deduit du libelle ou de la categorie). */
    public static AccountPurpose purposeStock(TypeArticle type) {
        return switch (type) {
            case MARCHANDISE -> AccountPurpose.STOCK_MARCHANDISE;
            case MATIERE_PREMIERE -> AccountPurpose.STOCK_MATIERE;
            case PRODUIT_FINI, PRODUIT_INTERMEDIAIRE -> AccountPurpose.STOCK_PRODUIT;
            case AUTRE -> AccountPurpose.STOCK_AUTRE;
        };
    }

    public CompteResolu compteStock(Entreprise entreprise, Article article, AccountingOperationType operation) {
        return accountingAccountResolver.resoudreCompte(entreprise.getId(), purposeStock(article.getType()),
                contexte(entreprise, article, operation),
                article.getCompteStock() != null ? article.getCompteStock().getId() : null);
    }

    public CompteResolu compteVariation(Entreprise entreprise, Article article, AccountingOperationType operation) {
        return accountingAccountResolver.resoudreCompte(entreprise.getId(), AccountPurpose.VARIATION_STOCK,
                contexte(entreprise, article, operation),
                article.getCompteVariation() != null ? article.getCompteVariation().getId() : null);
    }

    private static AccountContext contexte(Entreprise entreprise, Article article, AccountingOperationType operation) {
        return new AccountContext(operation, article.getType().name(), article.getId(), null, entreprise.getPays(), null, Map.of());
    }
}
