package com.example.comptable.agent.matching.repository;

import com.example.comptable.agent.matching.domain.AiMatchProposal;
import com.example.comptable.agent.matching.domain.SourceType;
import com.example.comptable.agent.matching.domain.StatutPropositionIA;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiMatchProposalRepository extends JpaRepository<AiMatchProposal, UUID> {

    List<AiMatchProposal> findByEntrepriseIdAndStatusOrderByCreatedAtDesc(UUID entrepriseId, StatutPropositionIA status);

    /** Mission P1.9 (KPI Matching Quality/False Auto-Match) : portefeuille complet du tenant,
     * toutes entreprises confondues, meme pattern que {@code FindingRepository.findByEntrepriseIdIn...}. */
    List<AiMatchProposal> findByEntrepriseIdIn(List<UUID> entrepriseIds);

    Optional<AiMatchProposal> findBySourceTypeAndSourceIdAndStatus(
            SourceType sourceType, String sourceId, StatutPropositionIA status);

    /** Un candidat "encore disponible" (garde-fou section 8) ne doit pas deja avoir ete accepte
     * pour une AUTRE source : evite qu'un meme candidat soit auto-attribue deux fois. */
    boolean existsByCandidateTypeAndCandidateIdAndStatus(
            SourceType candidateType, String candidateId, StatutPropositionIA status);
}
