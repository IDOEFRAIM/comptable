package com.example.comptable.stock.repository;

import com.example.comptable.stock.domain.Depot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepotRepository extends JpaRepository<Depot, UUID> {

    List<Depot> findByEntrepriseIdOrderByLibelleAsc(UUID entrepriseId);

    Optional<Depot> findByEntrepriseIdAndCode(UUID entrepriseId, String code);
}
