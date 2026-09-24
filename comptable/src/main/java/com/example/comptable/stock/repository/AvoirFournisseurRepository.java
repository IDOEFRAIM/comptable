package com.example.comptable.stock.repository;

import com.example.comptable.stock.domain.AvoirFournisseur;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AvoirFournisseurRepository extends JpaRepository<AvoirFournisseur, UUID> {

    boolean existsByFactureId(UUID factureId);

    Optional<AvoirFournisseur> findByFactureId(UUID factureId);

    List<AvoirFournisseur> findByEntrepriseId(UUID entrepriseId);

    long countByEntrepriseId(UUID entrepriseId);
}
