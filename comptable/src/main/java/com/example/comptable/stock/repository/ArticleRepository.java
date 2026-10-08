package com.example.comptable.stock.repository;

import org.springframework.data.jpa.repository.Query;
import com.example.comptable.stock.domain.Article;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleRepository extends JpaRepository<Article, UUID> {

    List<Article> findByEntrepriseIdOrderByDesignationAsc(UUID entrepriseId);

    Optional<Article> findByEntrepriseIdAndCodeArticle(UUID entrepriseId, String codeArticle);

    long countByEntrepriseId(UUID entrepriseId);

    /** Articles actifs a la quantite ou a la valeur manifestement incoherente (la politique de stock negatif est filtree cote detecteur). */
    @Query("select a from Article a where a.entreprise.id = :entrepriseId and a.actif = true and "
            + "(a.quantiteEnStock < 0 or a.valeurStockTotale < 0 or (a.quantiteEnStock = 0 and a.valeurStockTotale <> 0))")
    List<Article> rechercherIncoherents(UUID entrepriseId);
}
