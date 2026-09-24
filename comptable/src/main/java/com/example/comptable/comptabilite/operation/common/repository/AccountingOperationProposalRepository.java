package com.example.comptable.comptabilite.operation.common.repository;

import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountingOperationProposalRepository extends JpaRepository<AccountingOperationProposal, UUID> {

    /** Support de l'idempotence (section 24) : retrouve une proposition deja generee pour une meme
     * operation metier plutot que d'en creer une seconde. Voir AccountingOperationEngine.assemblerEtSauvegarder. */
    Optional<AccountingOperationProposal> findByEntrepriseIdAndSourceTypeAndSourceIdAndType(
            UUID entrepriseId, String sourceType, UUID sourceId, AccountingOperationType type);
}
