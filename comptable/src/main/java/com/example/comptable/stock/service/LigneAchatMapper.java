package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.operation.achat.LigneAchatOperationRequest;
import com.example.comptable.comptabilite.operation.achat.NatureAchat;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.CommandeAchat;
import com.example.comptable.stock.domain.LigneCommandeAchat;
import com.example.comptable.stock.domain.TypeArticle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Traduit les lignes d'un {@link CommandeAchat} (quantite/prix par article) en lignes d'achat
 * comprises par {@code AchatRule.construireMultiLigne} (compte/montant/TVA precalcules). Partagee
 * par {@link CommandeAchatService} (facture fournisseur) et {@code AvoirFournisseurService} (avoir
 * total) pour ne jamais dupliquer la reconstruction des lignes d'origine.
 */
final class LigneAchatMapper {

    private LigneAchatMapper() {
    }

    static List<LigneAchatOperationRequest> depuisCommande(CommandeAchat commande) {
        return commande.getLignes().stream()
                .map(LigneAchatMapper::depuisLigne)
                .toList();
    }

    private static LigneAchatOperationRequest depuisLigne(LigneCommandeAchat ligne) {
        Article article = ligne.getArticle();
        BigDecimal montantHt = ligne.getQuantite().multiply(ligne.getPrixUnitaireHt()).setScale(2, RoundingMode.HALF_UP);
        return new LigneAchatOperationRequest(article.getDesignation(), natureDepuisTypeArticle(article.getType()),
                montantHt, article.getTauxTva(), article.getCompteAchat().getId(), null);
    }

    private static NatureAchat natureDepuisTypeArticle(TypeArticle type) {
        return switch (type) {
            case MARCHANDISE -> NatureAchat.MARCHANDISE;
            case MATIERE_PREMIERE -> NatureAchat.MATIERE_PREMIERE;
            default -> NatureAchat.AUTRE;
        };
    }
}
