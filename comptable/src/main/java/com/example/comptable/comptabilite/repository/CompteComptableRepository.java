package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.CompteComptable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompteComptableRepository extends JpaRepository<CompteComptable, UUID> {

    List<CompteComptable> findByEntrepriseId(UUID entrepriseId);

    Optional<CompteComptable> findByEntrepriseIdAndNumero(UUID entrepriseId, String numero);

    List<CompteComptable> findByEntrepriseIdAndNumeroStartingWithOrderByNumeroDesc(UUID entrepriseId, String prefixe);
}
