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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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

    private final CompteComptableValidationService compteComptableValidationService;

    public ResultatRegle construire(Entreprise entreprise, Inventaire inventaire) {
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        List<String> explications = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (LigneInventaire ligne : inventaire.getLignes()) {
            if (ligne.getEcart().signum() == 0) {
                continue;
            }
            Article article = ligne.getArticle();
            CompteComptable compteStock;
            CompteComptable compteVariation;
            try {
                compteStock = compteComptableValidationService.chargerCompteUtilisable(
                        entreprise.getId(), article.getCompteStock().getId(), true);
                compteVariation = compteComptableValidationService.chargerCompteUtilisable(
                        entreprise.getId(), article.getCompteVariation().getId(), true);
            } catch (ResponseStatusException e) {
                explications.add("Compte de stock ou de variation non configure ou inactif pour l'article "
                        + article.getCodeArticle() + " - regularisation impossible pour cet article");
                continue;
            }

            String libelle = "Regularisation inventaire - " + article.getDesignation();
            BigDecimal montant = ligne.getValeurEcart().abs();
            if (ligne.getEcart().signum() > 0) {
                lignes.add(AccountingOperationProposalLigne.debit(compteStock, montant, libelle));
                lignes.add(AccountingOperationProposalLigne.credit(compteVariation, montant, libelle));
            } else {
                lignes.add(AccountingOperationProposalLigne.debit(compteVariation, montant, libelle));
                lignes.add(AccountingOperationProposalLigne.credit(compteStock, montant, libelle));
            }
        }

        return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
    }
}
