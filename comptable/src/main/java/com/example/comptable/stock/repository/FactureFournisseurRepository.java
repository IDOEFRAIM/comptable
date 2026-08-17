package com.example.comptable.stock.repository;

import com.example.comptable.stock.domain.FactureFournisseur;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FactureFournisseurRepository extends JpaRepository<FactureFournisseur, UUID> {

    long countByEntrepriseId(UUID entrepriseId);

    boolean existsByCommandeId(UUID commandeId);
}
