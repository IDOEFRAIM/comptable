package com.example.comptable.comptabilite.allocation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, UUID> {

    Optional<PaymentAllocation> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);

    @Query("select a from PaymentAllocation a where a.paymentLineId = :paymentLineId and a.invoiceLineId = :invoiceLineId "
            + "and a.status = com.example.comptable.comptabilite.allocation.StatutAllocation.ACTIVE")
    Optional<PaymentAllocation> findActive(UUID paymentLineId, UUID invoiceLineId);

    @Query("select a from PaymentAllocation a where a.paymentLineId = :paymentLineId "
            + "and a.status = com.example.comptable.comptabilite.allocation.StatutAllocation.ACTIVE order by a.createdAt")
    List<PaymentAllocation> findActivesDuPaiement(UUID paymentLineId);

    @Query("select a from PaymentAllocation a where a.invoiceLineId = :invoiceLineId "
            + "and a.status = com.example.comptable.comptabilite.allocation.StatutAllocation.ACTIVE order by a.createdAt")
    List<PaymentAllocation> findActivesDeLaFacture(UUID invoiceLineId);

    @Query("select coalesce(sum(a.allocatedAmount), 0) from PaymentAllocation a where a.paymentLineId = :paymentLineId "
            + "and a.status = com.example.comptable.comptabilite.allocation.StatutAllocation.ACTIVE")
    BigDecimal totalAlloueDuPaiement(UUID paymentLineId);

    @Query("select coalesce(sum(a.allocatedAmount), 0) from PaymentAllocation a where a.invoiceLineId = :invoiceLineId "
            + "and a.status = com.example.comptable.comptabilite.allocation.StatutAllocation.ACTIVE")
    BigDecimal totalAlloueDeLaFacture(UUID invoiceLineId);

    /** Une seule requete pour N factures : {@code [invoiceLineId, somme]}. */
    @Query("select a.invoiceLineId, sum(a.allocatedAmount) from PaymentAllocation a where a.invoiceLineId in :invoiceLineIds "
            + "and a.status = com.example.comptable.comptabilite.allocation.StatutAllocation.ACTIVE group by a.invoiceLineId")
    List<Object[]> totauxParFacture(Collection<UUID> invoiceLineIds);

    @Query("select a from PaymentAllocation a where a.entrepriseId = :entrepriseId order by a.createdAt desc")
    List<PaymentAllocation> findByEntreprise(UUID entrepriseId);

    /** Une seule requete pour N lignes de reglement : {@code [paymentLineId, somme]}. */
    @Query("select a.paymentLineId, sum(a.allocatedAmount) from PaymentAllocation a where a.paymentLineId in :paymentLineIds "
            + "and a.status = com.example.comptable.comptabilite.allocation.StatutAllocation.ACTIVE group by a.paymentLineId")
    List<Object[]> totauxParPaiement(Collection<UUID> paymentLineIds);

    /** Affectations actives touchant l'une des lignes (reglement OU facture) : base de l'investigation d'un tiers, en une requete. */
    @Query("select a from PaymentAllocation a where a.status = com.example.comptable.comptabilite.allocation.StatutAllocation.ACTIVE "
            + "and (a.paymentLineId in :ids or a.invoiceLineId in :ids) order by a.createdAt")
    List<PaymentAllocation> activesDesLignes(Collection<UUID> ids);
}
