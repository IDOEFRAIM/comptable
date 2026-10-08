package com.example.comptable.banque;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankInternalTransferRepository extends JpaRepository<BankInternalTransfer, UUID> {

    Optional<BankInternalTransfer> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);

    /** Virements vivants (proposes ou confirmes) dont l'une des jambes figure dans la liste : une seule requete pour tout un ecran. */
    @Query("select t from BankInternalTransfer t where t.status in (com.example.comptable.banque.StatutReconciliation.PROPOSED, "
            + "com.example.comptable.banque.StatutReconciliation.CONFIRMED) and (t.debitTransactionId in :ids or t.creditTransactionId in :ids)")
    List<BankInternalTransfer> findVivantsDont(Collection<UUID> ids);

    /** Couples rejetes ou annules : jamais reproposes par le systeme. */
    @Query("select t from BankInternalTransfer t where t.entrepriseId = :entrepriseId and t.status in "
            + "(com.example.comptable.banque.StatutReconciliation.REJECTED, com.example.comptable.banque.StatutReconciliation.REVERSED)")
    List<BankInternalTransfer> findExclus(UUID entrepriseId);

    @Query("select t from BankInternalTransfer t where t.entrepriseId = :entrepriseId and t.status = :status order by t.createdAt desc")
    List<BankInternalTransfer> findParStatut(UUID entrepriseId, StatutReconciliation status);
}
