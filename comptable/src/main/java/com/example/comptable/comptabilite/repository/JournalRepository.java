package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.Journal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalRepository extends JpaRepository<Journal, UUID> {

    List<Journal> findByEntrepriseId(UUID entrepriseId);

    Optional<Journal> findByEntrepriseIdAndCode(UUID entrepriseId, String code);
}
