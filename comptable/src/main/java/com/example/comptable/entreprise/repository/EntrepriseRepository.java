package com.example.comptable.entreprise.repository;

import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntrepriseRepository extends JpaRepository<Entreprise, UUID> {

    List<Entreprise> findByTenantId(UUID tenantId);
}
