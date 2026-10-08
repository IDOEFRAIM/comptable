package com.example.comptable.comptabilite.operation.stock;

import com.example.comptable.comptabilite.operation.common.Blocages;

import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.stock.domain.Article;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Contrepartie comptable d'un mouvement de stock en inventaire permanent (entree, sortie,
 * ajustement). Le module Stock calcule l'evenement economique (quantite, valeur au CUMP) ; cette regle
 * se contente de le traduire en lignes, sans jamais recalculer une valeur :
 * <ul>
 *   <li>entree en stock : Debit compte de stock / Credit compte de variation de stock ;</li>
 *   <li>sortie de stock : Debit compte de variation de stock / Credit compte de stock.</li>
 * </ul>
 * Les comptes viennent de {@link StockAccountResolver} ; un compte non configure, ambigu ou invalide
 * devient une explication (proposition NEEDS_REVIEW), jamais un compte devine ni une ligne omise.
 */
@Service
@RequiredArgsConstructor
public class StockMouvementRule {

    private final StockAccountResolver stockAccountResolver;

    public ResultatRegle construire(Entreprise entreprise, Article article, AccountingOperationType operation,
                                    boolean entreeEnStock, BigDecimal valeur, String libelle) {
        Blocages explications = new Blocages();
        CompteResolu stock = stockAccountResolver.compteStock(entreprise, article, operation);
        CompteResolu variation = stockAccountResolver.compteVariation(entreprise, article, operation);
        if (!stock.estResolu()) {
            explications.compte(stock, "de stock de l'article " + article.getCodeArticle());
        }
        if (!variation.estResolu()) {
            explications.compte(variation, "de variation de stock de l'article " + article.getCodeArticle());
        }
        if (!explications.isEmpty()) {
            return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, List.of(), explications, List.of());
        }

        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        if (entreeEnStock) {
            lignes.add(AccountingOperationProposalLigne.debit(stock.compte(), valeur, libelle));
            lignes.add(AccountingOperationProposalLigne.credit(variation.compte(), valeur, libelle));
        } else {
            lignes.add(AccountingOperationProposalLigne.debit(variation.compte(), valeur, libelle));
            lignes.add(AccountingOperationProposalLigne.credit(stock.compte(), valeur, libelle));
        }
        return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, List.of());
    }
}
