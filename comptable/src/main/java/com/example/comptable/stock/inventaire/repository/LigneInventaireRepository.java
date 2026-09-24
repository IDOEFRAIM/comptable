package com.example.comptable.stock.inventaire.repository;

import com.example.comptable.stock.inventaire.domain.LigneInventaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LigneInventaireRepository extends JpaRepository<LigneInventaire, UUID> {

    Optional<LigneInventaire> findByInventaireIdAndArticleId(UUID inventaireId, UUID articleId);

    @Query("select l from LigneInventaire l where l.inventaire.id = :inventaireId order by l.article.designation asc")
    List<LigneInventaire> findByInventaireIdFetchAll(UUID inventaireId);

    /** Dernier comptage valide/cloture pour un article - reference du "stock theorique" en mode
     * INTERMITTENT (aucun suivi continu n'existe pour ces articles). */
    @Query("""
            select l from LigneInventaire l
            where l.article.id = :articleId
              and l.inventaire.statut in ('VALIDE', 'CLOTURE')
            order by l.inventaire.date desc, l.inventaire.createdAt desc
            """)
    List<LigneInventaire> findDernieresValideesParArticle(UUID articleId);
}
