package com.example.comptable.etatsfinanciers.snapshot;

import com.example.comptable.etatsfinanciers.definition.StatementType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialStatementSnapshotRepository extends JpaRepository<FinancialStatementSnapshot, UUID> {

    List<FinancialStatementSnapshot> findByEntrepriseIdAndExerciceIdOrderByCreatedAtDesc(UUID entrepriseId, UUID exerciceId);

    Optional<FinancialStatementSnapshot> findByEntrepriseIdAndExerciceIdAndStatementTypeAndFrameworkAndDefinitionVersion(UUID entrepriseId, UUID exerciceId,
                                                                                                                            StatementType statementType, String framework,
                                                                                                                            String definitionVersion);

    Optional<FinancialStatementSnapshot> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);
}
