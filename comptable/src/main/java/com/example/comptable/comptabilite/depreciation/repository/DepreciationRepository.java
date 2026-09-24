package com.example.comptable.comptabilite.depreciation.repository;

import com.example.comptable.comptabilite.depreciation.domain.Depreciation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DepreciationRepository extends JpaRepository<Depreciation, UUID> {

    List<Depreciation> findByEntrepriseIdOrderByCreatedAtDesc(UUID entrepriseId);
}
