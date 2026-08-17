package com.example.comptable.stock.repository;

import com.example.comptable.stock.domain.CommandeAchat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface CommandeAchatRepository extends JpaRepository<CommandeAchat, UUID> {

    @Query("select distinct c from CommandeAchat c "
            + "join fetch c.fournisseur "
            + "left join fetch c.lignes l left join fetch l.article "
            + "where c.entreprise.id = :entrepriseId "
            + "order by c.dateCommande desc, c.numero desc")
    List<CommandeAchat> findByEntrepriseIdFetchAll(UUID entrepriseId);

    long countByEntrepriseId(UUID entrepriseId);
}
