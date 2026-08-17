package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.web.dto.ArticleDto;
import com.example.comptable.stock.web.dto.CreerArticleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Catalogue d'articles. Les 4 comptes comptables associes a un article (stock/achat/vente/
 * variation) sont toujours choisis explicitement par l'utilisateur a la creation, jamais devines
 * a partir du libelle ou de la categorie : meme philosophie que le choix de compte de produit
 * dans FacturationService.
 */
@Service
@RequiredArgsConstructor
public class ArticleService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final ArticleRepository articleRepository;
    private final CompteComptableRepository compteComptableRepository;

    @Transactional
    public ArticleDto creer(UUID entrepriseId, CreerArticleRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        if (articleRepository.findByEntrepriseIdAndCodeArticle(entrepriseId, request.codeArticle()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un article avec le code " + request.codeArticle() + " existe deja");
        }

        Article article = new Article();
        article.setEntreprise(entreprise);
        article.setCodeArticle(request.codeArticle());
        article.setDesignation(request.designation());
        article.setCategorie(request.categorie());
        article.setUniteMesure(request.uniteMesure());
        article.setPrixVenteHt(request.prixVenteHt());
        article.setTauxTva(request.tauxTva());
        article.setCompteStock(chargerCompte(entrepriseId, request.compteStockId(), "de stock"));
        article.setCompteAchat(chargerCompte(entrepriseId, request.compteAchatId(), "d'achat"));
        article.setCompteVente(chargerCompte(entrepriseId, request.compteVenteId(), "de vente"));
        article.setCompteVariation(chargerCompte(entrepriseId, request.compteVariationId(), "de variation de stock"));

        return ArticleDto.from(articleRepository.save(article));
    }

    @Transactional(readOnly = true)
    public List<ArticleDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return articleRepository.findByEntrepriseIdOrderByDesignationAsc(entrepriseId).stream()
                .map(ArticleDto::from)
                .toList();
    }

    /** Charge l'entite (pas le DTO) pour un service appelant, ex. {@link MouvementStockService}. */
    @Transactional(readOnly = true)
    public Article chargerEntite(UUID entrepriseId, UUID articleId) {
        return articleRepository.findById(articleId)
                .filter(a -> a.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article introuvable"));
    }

    private CompteComptable chargerCompte(UUID entrepriseId, UUID compteId, String role) {
        return compteComptableRepository.findById(compteId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte " + role + " introuvable"));
    }
}
