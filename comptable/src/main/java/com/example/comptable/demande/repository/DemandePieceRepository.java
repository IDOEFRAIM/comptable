package com.example.comptable.demande.repository;

import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DemandePieceRepository extends JpaRepository<DemandePiece, UUID> {

    @Query("select d from DemandePiece d left join fetch d.demandeePar "
            + "where d.entreprise.id = :entrepriseId order by d.createdAt desc")
    List<DemandePiece> findByEntrepriseIdOrderByCreatedAtDesc(UUID entrepriseId);

    @Query("select d from DemandePiece d left join fetch d.demandeePar "
            + "where d.entreprise.id = :entrepriseId and d.statut = :statut order by d.createdAt desc")
    List<DemandePiece> findByEntrepriseIdAndStatutOrderByCreatedAtDesc(UUID entrepriseId, StatutDemandePiece statut);

    /** Demandes toujours EN_ATTENTE dont la date limite est deja passee, tous clients confondus -
     * utilise par RelancePieceService pour le batch de relance quotidien. join fetch sur
     * entreprise (toujours necessaire, jamais nulle) et demandeePar (optionnel : left join, sinon
     * les demandes sans destinataire de notification interne seraient silencieusement exclues). */
    @Query("select d from DemandePiece d join fetch d.entreprise left join fetch d.demandeePar "
            + "where d.statut = :statut and d.dateLimite < :date order by d.dateLimite asc")
    List<DemandePiece> findEnRetard(@Param("statut") StatutDemandePiece statut, @Param("date") LocalDate date);

    /** Garde-fou anti-doublon (mission P0, boucle de resolution WhatsApp) : une demande a deja
     * ete creee pour ce Finding, ne pas re-solliciter le client a chaque nouveau passage du
     * rapprochement automatique. */
    boolean existsByFindingId(UUID findingId);

    /** Mission P1 (Cabinet Cockpit) : variante tenant-scopee de {@link #findEnRetard} - celle-ci
     * est volontairement globale (tous tenants confondus) car reservee au job de relance en
     * arriere-plan ; la reutiliser telle quelle pour une vue cabinet aurait fuite les demandes en
     * retard d'autres cabinets. */
    long countByEntrepriseIdInAndStatutAndDateLimiteBefore(List<UUID> entrepriseIds, StatutDemandePiece statut, LocalDate date);

    /** Mission P1 (section 12, Work Queue unifiee) : les demandes en attente entrent dans la meme
     * inbox que les Finding, sans nouvelle table de jonction. */
    List<DemandePiece> findByEntrepriseIdInAndStatutOrderByDateLimiteAsc(List<UUID> entrepriseIds, StatutDemandePiece statut);

    /** Mission P1 (Cabinet Analytics, section 36) : taux de demandes resolues sur tout le
     * portefeuille. */
    long countByEntrepriseIdInAndStatut(List<UUID> entrepriseIds, StatutDemandePiece statut);
}
