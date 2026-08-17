package com.example.comptable.stock.repository;

import com.example.comptable.stock.domain.Article;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleRepository extends JpaRepository<Article, UUID> {

    List<Article> findByEntrepriseIdOrderByDesignationAsc(UUID entrepriseId);

    Optional<Article> findByEntrepriseIdAndCodeArticle(UUID entrepriseId, String codeArticle);

    long countByEntrepriseId(UUID entrepriseId);
}
