package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.ReleveBancaire;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReleveBancaireRepository extends JpaRepository<ReleveBancaire, UUID> {

    List<ReleveBancaire> findByEntrepriseIdOrderByDateFinDesc(UUID entrepriseId);
}
