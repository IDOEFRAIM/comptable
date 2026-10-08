package com.example.comptable.agent.imputation.repository;

import com.example.comptable.agent.imputation.domain.ImputationSuggestion;
import com.example.comptable.agent.imputation.domain.StatutImputationSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImputationSuggestionRepository extends JpaRepository<ImputationSuggestion, UUID> {

    Optional<ImputationSuggestion> findByDocumentIdAndStatus(UUID documentId, StatutImputationSuggestion status);

    boolean existsByDocumentIdAndStatus(UUID documentId, StatutImputationSuggestion status);

    /** Mission P1.9 (KPI Matching Quality/Human Touch Rate) : portefeuille complet du tenant. */
    List<ImputationSuggestion> findByEntrepriseIdIn(List<UUID> entrepriseIds);
}
