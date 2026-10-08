package com.example.comptable.comptabilite.operation.inventaire;

import com.example.comptable.comptabilite.operation.common.Blocages;

import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.stock.StockAccountResolver;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.inventaire.domain.Inventaire;
import com.example.comptable.stock.inventaire.domain.LigneInventaire;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Regularisation d'un ecart d'inventaire physique : une paire de lignes par article en ecart
 * (jamais de regroupement agrege par compte - chaque paire est intrinsequement equilibree, et
 * directement tracable article par article). Surplus (ecart &gt; 0) : Debit compte de stock /
 * Credit compte de variation. Perte (ecart &lt; 0) : Debit compte de variation / Credit compte de
 * stock. Les comptes de l'article (deja choisis explicitement a sa creation, jamais devines ici)
 * sont re-valides via {@link CompteComptableValidationService} - un compte desactive entre la
 * saisie de l'inventaire et sa validation produit une explication ("Compte ... non configure ou
 * inactif"), jamais une ecriture incorrecte.
 */
@Service
@RequiredArgsConstructor
public class InventaireRegularisationRule {

    private final StockAccountResolver stockAccountResolver;

    public ResultatRegle construire(Entreprise entreprise, Inventaire inventaire) {
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        Blocages explications = new Blocages();
        List<String> warnings = new ArrayList<>();

        for (LigneInventaire ligne : inventaire.getLignes()) {
            if (ligne.getEcart().signum() == 0) {
                continue;
            }
            Article article = ligne.getArticle();
            CompteResolu stock = stockAccountResolver.compteStock(entreprise, article, AccountingOperationType.REGULARISATION_INVENTAIRE);
            CompteResolu variation = stockAccountResolver.compteVariation(entreprise, article, AccountingOperationType.REGULARISATION_INVENTAIRE);
            if (!stock.estResolu() || !variation.estResolu()) {
                // Aucune ligne pour cet article : la proposition passe en NEEDS_REVIEW avec la cause exacte.
                if (!stock.estResolu()) {
                    explications.add(stock.explicationBlocage("de stock de l'article " + article.getCodeArticle())
                            + " - regularisation impossible pour cet article");
                }
                if (!variation.estResolu()) {
                    explications.add(variation.explicationBlocage("de variation de stock de l'article " + article.getCodeArticle())
                            + " - regularisation impossible pour cet article");
                }
                continue;
            }

            String libelle = "Regularisation inventaire - " + article.getDesignation();
            BigDecimal montant = ligne.getValeurEcart().abs();
            if (ligne.getEcart().signum() > 0) {
                lignes.add(AccountingOperationProposalLigne.debit(stock.compte(), montant, libelle));
                lignes.add(AccountingOperationProposalLigne.credit(variation.compte(), montant, libelle));
            } else {
                lignes.add(AccountingOperationProposalLigne.debit(variation.compte(), montant, libelle));
                lignes.add(AccountingOperationProposalLigne.credit(stock.compte(), montant, libelle));
            }
        }

        return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
    }
}
