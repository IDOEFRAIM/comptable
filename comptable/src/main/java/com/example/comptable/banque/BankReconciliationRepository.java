package com.example.comptable.banque;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankReconciliationRepository extends JpaRepository<BankReconciliation, UUID> {

    Optional<BankReconciliation> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);

    @Query("select r from BankReconciliation r where r.bankTransactionId = :transactionId and r.status = "
            + "com.example.comptable.banque.StatutReconciliation.CONFIRMED")
    Optional<BankReconciliation> findConfirmeeDe(UUID transactionId);

    @Query("select r from BankReconciliation r where r.targetType = 'LIGNE_ECRITURE' and r.targetId = :targetId and r.status = "
            + "com.example.comptable.banque.StatutReconciliation.CONFIRMED")
    Optional<BankReconciliation> findConfirmeeSurCible(UUID targetId);

    /** L'etat vivant (propose, confirme ou rejete) d'un couple transaction / cible. */
    @Query("select r from BankReconciliation r where r.bankTransactionId = :transactionId and r.targetType = 'LIGNE_ECRITURE' "
            + "and r.targetId = :targetId and r.status in (com.example.comptable.banque.StatutReconciliation.PROPOSED, "
            + "com.example.comptable.banque.StatutReconciliation.CONFIRMED, com.example.comptable.banque.StatutReconciliation.REJECTED)")
    Optional<BankReconciliation> findVivant(UUID transactionId, UUID targetId);

    /** Une seule requete pour N transactions : rapprochements proposes ou confirmes. */
    @Query("select r from BankReconciliation r where r.bankTransactionId in :transactionIds and r.status in "
            + "(com.example.comptable.banque.StatutReconciliation.PROPOSED, com.example.comptable.banque.StatutReconciliation.CONFIRMED)")
    List<BankReconciliation> findActifsDe(Collection<UUID> transactionIds);

    /** Couples rejetes OU annules par un humain : le systeme ne les reproposera ni ne les reconfirmera jamais. */
    @Query("select r from BankReconciliation r where r.bankTransactionId in :transactionIds and r.status in "
            + "(com.example.comptable.banque.StatutReconciliation.REJECTED, com.example.comptable.banque.StatutReconciliation.REVERSED)")
    List<BankReconciliation> findRejetesDe(Collection<UUID> transactionIds);

    boolean existsByBankTransactionIdAndTargetTypeAndTargetIdAndStatus(UUID bankTransactionId, String targetType, UUID targetId, StatutReconciliation status);

    @Query("select count(r) from BankReconciliation r where r.entrepriseId = :entrepriseId and r.status = :status")
    long compter(UUID entrepriseId, StatutReconciliation status);

    @Query("select count(r) from BankReconciliation r where r.entrepriseId = :entrepriseId and r.status = "
            + "com.example.comptable.banque.StatutReconciliation.CONFIRMED and r.source = :source")
    long compterConfirmeesParSource(UUID entrepriseId, SourceReconciliation source);

    /** Delai moyen (secondes) entre l'import du releve et la confirmation d'un rapprochement, hors reprise de l'historique. */
    @Query(value = "select avg(extract(epoch from (b.confirmed_at - rel.created_at))) from bank_reconciliation b "
            + "join ligne_releve l on l.id = b.bank_transaction_id join releve_bancaire rel on rel.id = l.releve_id "
            + "where b.entreprise_id = :entrepriseId and b.status = 'CONFIRMED' and b.source <> 'LEGACY' and b.confirmed_at is not null",
            nativeQuery = true)
    Double tempsMoyenSecondes(UUID entrepriseId);

    @Query("select r from BankReconciliation r where r.bankTransactionId in :transactionIds and r.status = "
            + "com.example.comptable.banque.StatutReconciliation.CONFIRMED")
    List<BankReconciliation> findConfirmeesDe(Collection<UUID> transactionIds);

    @Query("select r from BankReconciliation r where r.targetType = 'LIGNE_ECRITURE' and r.targetId in :targetIds and r.status = "
            + "com.example.comptable.banque.StatutReconciliation.CONFIRMED")
    List<BankReconciliation> findConfirmeesSurCibles(Collection<UUID> targetIds);

    /** Etat vivant (propose, confirme ou rejete) de toutes les paires des transactions donnees : une seule requete. */
    @Query("select r from BankReconciliation r where r.bankTransactionId in :transactionIds and r.status in "
            + "(com.example.comptable.banque.StatutReconciliation.PROPOSED, com.example.comptable.banque.StatutReconciliation.CONFIRMED, "
            + "com.example.comptable.banque.StatutReconciliation.REJECTED)")
    List<BankReconciliation> findVivantsDe(Collection<UUID> transactionIds);

    /** Rapprochements annules par un humain, par cible : l'historique d'un reglement dont le lien bancaire a ete defait. */
    @Query("select r from BankReconciliation r where r.targetType = 'LIGNE_ECRITURE' and r.targetId in :targetIds and r.status = "
            + "com.example.comptable.banque.StatutReconciliation.REVERSED")
    List<BankReconciliation> findInversesSurCibles(Collection<UUID> targetIds);

    /** Tous les rapprochements d'une transaction, quel que soit leur etat (chronologie d'investigation). */
    @Query("select r from BankReconciliation r where r.bankTransactionId = :transactionId order by r.createdAt")
    List<BankReconciliation> findHistoriqueDe(UUID transactionId);
}
