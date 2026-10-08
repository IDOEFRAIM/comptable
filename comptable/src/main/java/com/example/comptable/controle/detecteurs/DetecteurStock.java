package com.example.comptable.controle.detecteurs;

import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.PolitiqueStockNegatif;
import com.example.comptable.stock.repository.ArticleRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code STOCK_INCONSISTENCY} : article actif dont la quantite ou la valeur de stock est
 * manifestement incoherente - quantite negative alors que la politique de l'article l'interdit,
 * valeur negative, ou valeur non nulle pour une quantite nulle. Une quantite negative AUTORISEE par
 * la politique de l'article n'est pas une anomalie.
 */
@Component
public class DetecteurStock implements ControlDetector {

    private final ArticleRepository articleRepository;

    public DetecteurStock(ArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
    }

    @Override
    public String nom() {
        return "stock";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.STOCK_INCONSISTENCY);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        return articleRepository.rechercherIncoherents(entreprise.getId()).stream()
                .filter(a -> !(a.getQuantiteEnStock().signum() < 0 && a.getValeurStockTotale().signum() <= 0
                        && a.getPolitiqueStockNegatif() == PolitiqueStockNegatif.AUTORISE))
                .map(a -> constat(entreprise, a))
                .toList();
    }

    private ControlResult constat(Entreprise entreprise, Article a) {
        String cause;
        if (a.getQuantiteEnStock().signum() < 0) {
            cause = "quantite en stock negative (" + a.getQuantiteEnStock().toPlainString() + ") alors que la politique de l'article est "
                    + a.getPolitiqueStockNegatif();
        } else if (a.getValeurStockTotale().signum() < 0) {
            cause = "valeur de stock negative (" + a.getValeurStockTotale().toPlainString() + ")";
        } else {
            cause = "valeur de stock de " + a.getValeurStockTotale().toPlainString() + " pour une quantite nulle";
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("quantiteEnStock", a.getQuantiteEnStock());
        metadata.put("valeurStockTotale", a.getValeurStockTotale());
        metadata.put("pump", a.getPump());
        metadata.put("politiqueStockNegatif", a.getPolitiqueStockNegatif().name());
        return ControlResult.ouvert(ControlCodes.STOCK_INCONSISTENCY, ControlCategory.STOCK, ControlSeverity.WARNING,
                entreprise.getId(), "ARTICLE", a.getId().toString(), "Stock incoherent : " + a.getDesignation(),
                "Article " + a.getCodeArticle() + " : " + cause + ".", List.of(), metadata,
                "Verifier les mouvements de stock et regulariser par un inventaire ou un ajustement.");
    }
}
