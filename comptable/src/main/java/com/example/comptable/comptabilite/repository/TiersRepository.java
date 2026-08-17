package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TiersRepository extends JpaRepository<Tiers, UUID> {

    List<Tiers> findByEntrepriseId(UUID entrepriseId);

    List<Tiers> findByEntrepriseIdAndType(UUID entrepriseId, TypeTiers type);

    long countByEntrepriseIdAndType(UUID entrepriseId, TypeTiers type);
}
