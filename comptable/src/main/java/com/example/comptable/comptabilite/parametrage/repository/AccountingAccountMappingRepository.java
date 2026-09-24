package com.example.comptable.comptabilite.parametrage.repository;

import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.domain.AccountingAccountMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountingAccountMappingRepository extends JpaRepository<AccountingAccountMapping, UUID> {

    List<AccountingAccountMapping> findByEntrepriseId(UUID entrepriseId);

    List<AccountingAccountMapping> findByEntrepriseIdAndPurpose(UUID entrepriseId, AccountPurpose purpose);

    /** Tier 2 (mapping specifique) : contextKey compare a l'egalite stricte, jamais NULL ici. */
    List<AccountingAccountMapping> findByEntrepriseIdAndPurposeAndContextKeyAndActifTrue(
            UUID entrepriseId, AccountPurpose purpose, String contextKey);

    /** Tier 3 (mapping generique) : contextKey IS NULL. */
    List<AccountingAccountMapping> findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(
            UUID entrepriseId, AccountPurpose purpose);

    long countByCompteIdAndActifTrue(UUID compteId);
}
