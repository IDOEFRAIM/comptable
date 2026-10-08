package com.example.comptable.comptabilite.operation.common.repository;

import com.example.comptable.comptabilite.operation.common.domain.StatutOperationProposal;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountingOperationProposalRepository extends JpaRepository<AccountingOperationProposal, UUID> {

    /** Consequence comptable ACTIVE (non rejetee) d'une cause metier : la cle d'idempotence metier. Au plus une
     * (garanti par l'index unique partiel {@code uq_aop_source_event}). */
    Optional<AccountingOperationProposal> findFirstByEntrepriseIdAndSourceTypeAndSourceIdAndSourceEventAndStatutNot(
            UUID entrepriseId, String sourceType, UUID sourceId, String sourceEvent, StatutOperationProposal statut);

    /** Toutes les consequences comptables d'une cause metier, tous evenements et statuts confondus (tracabilite). */
    List<AccountingOperationProposal> findByEntrepriseIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc(
            UUID entrepriseId, String sourceType, UUID sourceId);

    /** Propositions ayant produit une ecriture donnee - utilise a la suppression d'un brouillon pour
     * ne laisser aucune proposition pointer vers une ecriture disparue (FK ecriture_id). */
    List<AccountingOperationProposal> findByEcritureId(UUID ecritureId);

    /** Lineage en une requete : propositions ayant produit chacune des ecritures donnees (drill-down des etats financiers). */
    @Query("select p from AccountingOperationProposal p where p.ecriture.id in :ecritureIds")
    List<AccountingOperationProposal> findByEcritureIdIn(java.util.Collection<UUID> ecritureIds);

    /**
     * Achats approuves de l'entreprise ayant DEBITE ce compte du montant donne : candidats a avoir deja
     * comptabilise une acquisition (garde contre le double comptage facture / immobilisation).
     */
    @Query("select distinct p from AccountingOperationProposal p join p.lignes l "
            + "where p.entreprise.id = :entrepriseId and p.statut = com.example.comptable.comptabilite.operation.common.domain.StatutOperationProposal.APPROVED "
            + "and p.type = com.example.comptable.comptabilite.operation.common.AccountingOperationType.ACHAT "
            + "and l.compte.id = :compteId and l.debit = :montant")
    List<AccountingOperationProposal> rechercherAchatsDebitant(UUID entrepriseId, UUID compteId, java.math.BigDecimal montant);

    /** Achats approuves qui debitent ce compte (candidats au rattachement d'une immobilisation), les plus recents d'abord. */
    @Query("select distinct p from AccountingOperationProposal p join fetch p.lignes l "
            + "where p.entreprise.id = :entrepriseId and p.statut = com.example.comptable.comptabilite.operation.common.domain.StatutOperationProposal.APPROVED "
            + "and p.type = com.example.comptable.comptabilite.operation.common.AccountingOperationType.ACHAT "
            + "and exists (select 1 from AccountingOperationProposalLigne l2 where l2.proposal = p and l2.compte.id = :compteId and l2.debit > 0) "
            + "order by p.dateOperation desc")
    List<AccountingOperationProposal> rechercherAchatsSurCompte(UUID entrepriseId, UUID compteId);

    /** Une ecriture d'achat issue du moteur, sans aucune piece justificative rattachee. */
    record PieceManquanteRow(UUID propositionId, UUID ecritureId, String numeroPiece, String sourceType, UUID sourceId,
                             String libelle, java.math.BigDecimal montant, java.time.LocalDate dateOperation, UUID tiersId, String tiersNom) {
    }

    @Query("select new com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository$PieceManquanteRow("
            + "p.id, e.id, e.numeroPiece, p.sourceType, p.sourceId, p.libelle, p.montantTotalDebit, p.dateOperation, t.id, t.nom) "
            + "from AccountingOperationProposal p join p.ecriture e left join p.tiers t "
            + "where p.entreprise.id = :entrepriseId and p.statut = com.example.comptable.comptabilite.operation.common.domain.StatutOperationProposal.APPROVED "
            + "and p.type in :types and not exists (select 1 from Document d where d.ecritureId = e.id) "
            + "and not exists (select 1 from Document d2, com.example.comptable.stock.domain.FactureFournisseur ff "
            + "where d2.cibleType = 'FACTURE_FOURNISSEUR' and d2.cibleId = ff.id and ff.ecritureId = e.id and d2.doublonDeId is null) "
            + "order by p.dateOperation asc")
    List<PieceManquanteRow> rechercherSansPiece(UUID entrepriseId, java.util.Collection<com.example.comptable.comptabilite.operation.common.AccountingOperationType> types);
}
