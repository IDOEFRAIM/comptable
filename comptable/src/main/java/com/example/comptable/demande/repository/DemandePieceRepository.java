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

    /** Demandes en attente du client dont la prochaine relance est due (bornees, plus anciennes d'abord). */
    @Query("select d from DemandePiece d join fetch d.entreprise where d.statut = com.example.comptable.demande.domain.StatutDemandePiece.EN_ATTENTE "
            + "and d.prochaineRelanceAt is not null and d.prochaineRelanceAt <= :maintenant order by d.prochaineRelanceAt asc")
    List<DemandePiece> findARelancer(java.time.OffsetDateTime maintenant, org.springframework.data.domain.Pageable page);

    /** Idem, restreint a une entreprise (evaluation ciblee d'un dossier : jamais les demandes des autres). */
    @Query("select d from DemandePiece d join fetch d.entreprise where d.statut = com.example.comptable.demande.domain.StatutDemandePiece.EN_ATTENTE "
            + "and d.entreprise.id = :entrepriseId and d.prochaineRelanceAt is not null and d.prochaineRelanceAt <= :maintenant order by d.prochaineRelanceAt asc")
    List<DemandePiece> findARelancerPourEntreprise(java.util.UUID entrepriseId, java.time.OffsetDateTime maintenant, org.springframework.data.domain.Pageable page);

    /** Idem pour l'expiration, restreinte a une entreprise. */
    @Query("select d from DemandePiece d where d.statut = com.example.comptable.demande.domain.StatutDemandePiece.EN_ATTENTE and d.entreprise.id = :entrepriseId and d.sentAt < :avant")
    List<DemandePiece> findAExpirerPourEntreprise(java.util.UUID entrepriseId, java.time.OffsetDateTime avant);

    /** Demandes sans reponse depuis trop longtemps (expiration). */
    @Query("select d from DemandePiece d where d.statut = com.example.comptable.demande.domain.StatutDemandePiece.EN_ATTENTE and d.sentAt < :avant")
    List<DemandePiece> findAExpirer(java.time.OffsetDateTime avant);

    /** Demandes ouvertes d'une entreprise (matching des pieces recues, compteurs de collecte). */
    @Query("select d from DemandePiece d where d.entreprise.id = :entrepriseId and d.statut in :statuts order by d.createdAt")
    List<DemandePiece> findOuvertes(UUID entrepriseId, java.util.Collection<StatutDemandePiece> statuts);

    java.util.Optional<DemandePiece> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);

    /** Demande ouverte pour cette ecriture (au plus une, garanti par un index unique partiel). */
    @Query("select d from DemandePiece d where d.entreprise.id = :entrepriseId and d.ecritureId = :ecritureId and d.statut in :statuts")
    java.util.Optional<DemandePiece> findOuverteParEcriture(UUID entrepriseId, UUID ecritureId, java.util.Collection<StatutDemandePiece> statuts);

    long countByEntrepriseIdAndStatut(UUID entrepriseId, StatutDemandePiece statut);

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

    /** Demandes ouvertes (demandees ou recues, pas encore rapprochees) reclamant le justificatif de cette ecriture. */
    List<DemandePiece> findByEntrepriseIdAndEcritureIdAndStatutIn(UUID entrepriseId, UUID ecritureId, java.util.Collection<StatutDemandePiece> statuts);

    /** Demandes de piece (toutes) liees a un lot d'ecritures, la plus recente d'abord. */
    @Query("select d from DemandePiece d where d.entreprise.id = :entrepriseId and d.ecritureId in :ecritureIds order by d.createdAt desc")
    List<DemandePiece> deLEcritures(UUID entrepriseId, java.util.Collection<UUID> ecritureIds);
}
