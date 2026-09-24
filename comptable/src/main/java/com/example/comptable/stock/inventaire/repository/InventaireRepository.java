package com.example.comptable.stock.inventaire.repository;

import com.example.comptable.stock.inventaire.domain.Inventaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface InventaireRepository extends JpaRepository<Inventaire, UUID> {

    @Query("select i from Inventaire i where i.entreprise.id = :entrepriseId order by i.date desc, i.createdAt desc")
    List<Inventaire> findByEntrepriseIdOrderByDateDesc(UUID entrepriseId);
}
