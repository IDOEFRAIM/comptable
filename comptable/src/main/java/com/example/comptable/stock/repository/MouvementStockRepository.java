package com.example.comptable.stock.repository;

import com.example.comptable.stock.domain.MouvementStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MouvementStockRepository extends JpaRepository<MouvementStock, UUID> {

    @Query("select m from MouvementStock m join fetch m.article "
            + "where m.article.id = :articleId order by m.dateMouvement desc, m.createdAt desc")
    List<MouvementStock> findByArticleIdFetchAll(UUID articleId);

    long countByArticle_Entreprise_Id(UUID entrepriseId);

    List<MouvementStock> findBySourceTypeAndSourceId(String sourceType, UUID sourceId);
}
