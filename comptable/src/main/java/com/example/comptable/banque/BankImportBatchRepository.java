package com.example.comptable.banque;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankImportBatchRepository extends JpaRepository<BankImportBatch, UUID> {

    List<BankImportBatch> findByEntrepriseIdOrderByImportedAtDesc(UUID entrepriseId);

    Optional<BankImportBatch> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);
}
