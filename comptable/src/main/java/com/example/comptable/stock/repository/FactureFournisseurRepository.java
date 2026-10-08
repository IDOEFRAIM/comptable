package com.example.comptable.stock.repository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import com.example.comptable.stock.domain.FactureFournisseur;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FactureFournisseurRepository extends JpaRepository<FactureFournisseur, UUID> {

    long countByEntrepriseId(UUID entrepriseId);

    boolean existsByCommandeId(UUID commandeId);

    /** Factures d'un fournisseur pouvant etre la facture d'une piece : celles qu'aucune AUTRE piece n'a deja rapprochees. */
    @Query("select f from FactureFournisseur f where f.entreprise.id = :entrepriseId and f.fournisseur.id = :fournisseurId "
            + "and not exists (select 1 from Document d where d.cibleType = 'FACTURE_FOURNISSEUR' and d.cibleId = f.id "
            + "and d.id <> :documentId and d.statutRapprochement = com.example.comptable.document.domain.StatutRapprochementDocument.RAPPROCHE) "
            + "order by f.dateFacture desc")
    List<FactureFournisseur> rechercherCandidates(UUID entrepriseId, UUID fournisseurId, UUID documentId);

    java.util.Optional<FactureFournisseur> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);
}
