package com.example.comptable.agent.comptabilisation.repository;

import com.example.comptable.agent.comptabilisation.domain.AccountingProposal;
import com.example.comptable.agent.comptabilisation.domain.StatutAccountingProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountingProposalRepository extends JpaRepository<AccountingProposal, UUID> {

    Optional<AccountingProposal> findByDocumentIdAndStatus(UUID documentId, StatutAccountingProposal status);

    boolean existsByDocumentIdAndStatus(UUID documentId, StatutAccountingProposal status);

    /** Mission P1.9 (KPI Matching Quality/Human Touch Rate) : portefeuille complet du tenant. */
    List<AccountingProposal> findByEntrepriseIdIn(List<UUID> entrepriseIds);
}
