package com.example.comptable.agent.finding.repository;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<Finding, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Finding> {

    /** Tri par severite : CRITICAL/IMPORTANT/REVIEW se trient deja alphabetiquement dans cet
     * ordre - pas besoin d'un CASE WHEN pour l'Inbox (mission section 9). */
    List<Finding> findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(UUID entrepriseId, StatutFinding status);

    long countByEntrepriseIdAndStatusAndSeverity(UUID entrepriseId, StatutFinding status, SeveriteFinding severity);

    long countByEntrepriseIdAndStatusAndType(UUID entrepriseId, StatutFinding status, TypeFinding type);

    /** Garde-fou anti-doublon : les moteurs (ControlEngine, MissingEvidenceAnalyzer) tournent a
     * chaque import/analyse et ne doivent pas recreer un finding deja ouvert pour la meme source. */
    Optional<Finding> findByEntrepriseIdAndSourceTypeAndSourceIdAndStatus(
            UUID entrepriseId, String sourceType, String sourceId, StatutFinding status);

    boolean existsByEntrepriseIdAndSourceTypeAndSourceIdAndStatus(
            UUID entrepriseId, String sourceType, String sourceId, StatutFinding status);

    /** Mission P1 (Cabinet Cockpit, section 54 - "eviter les appels en cascade") : une seule
     * requete batchee pour tout le portefeuille du cabinet plutot qu'une requete par entreprise. */
    List<Finding> findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(List<UUID> entrepriseIds, StatutFinding status);

    /** Mission P1 (Cabinet Analytics, section 36) : findings resolus, tout le portefeuille - sert
     * a calculer un temps moyen de resolution reel (createdAt -&gt; resolvedAt). */
    List<Finding> findByEntrepriseIdInAndStatus(List<UUID> entrepriseIds, StatutFinding status);

    /** Le constat ACTIF (OPEN ou IN_PROGRESS) d'un controle sur un objet : cle de deduplication metier (unique en base). */
    @Query("select f from Finding f where f.entreprise.id = :entrepriseId and f.controlCode = :controlCode "
            + "and f.sourceType = :entityType and f.sourceId = :entityId and f.status in (com.example.comptable.agent.finding.domain.StatutFinding.OPEN, com.example.comptable.agent.finding.domain.StatutFinding.IN_PROGRESS)")
    Optional<Finding> findActif(UUID entrepriseId, String controlCode, String entityType, String entityId);

    boolean existsByEntrepriseIdAndControlCodeAndSourceTypeAndSourceIdAndStatus(
            UUID entrepriseId, String controlCode, String sourceType, String sourceId, StatutFinding status);

    /** Constats actifs d'un ensemble de controles : sert a fermer ceux dont la cause a disparu. */
    @Query("select f from Finding f where f.entreprise.id = :entrepriseId and f.controlCode in :codes "
            + "and f.status in (com.example.comptable.agent.finding.domain.StatutFinding.OPEN, com.example.comptable.agent.finding.domain.StatutFinding.IN_PROGRESS)")
    List<Finding> findActifsParCodes(UUID entrepriseId, Collection<String> codes);

    /** Tous les constats actifs attaches a un objet (quel que soit le controle). */
    @Query("select f from Finding f where f.entreprise.id = :entrepriseId and f.sourceType = :entityType "
            + "and f.sourceId = :entityId and f.status in (com.example.comptable.agent.finding.domain.StatutFinding.OPEN, com.example.comptable.agent.finding.domain.StatutFinding.IN_PROGRESS)")
    List<Finding> findActifsParObjet(UUID entrepriseId, String entityType, String entityId);

    /** Ligne d'agregat de la synthese de revue : un seul GROUP BY pour toutes les tuiles. */
    record AgregatRevue(com.example.comptable.controle.ControlSeverity severite, com.example.comptable.controle.ControlCategory categorie,
                        String code, long nombre) {
    }

    @Query("select new com.example.comptable.agent.finding.repository.FindingRepository$AgregatRevue("
            + "f.controlSeverity, f.category, f.controlCode, count(f)) from Finding f "
            + "where f.entreprise.id = :entrepriseId and f.status in (com.example.comptable.agent.finding.domain.StatutFinding.OPEN, "
            + "com.example.comptable.agent.finding.domain.StatutFinding.IN_PROGRESS) "
            + "group by f.controlSeverity, f.category, f.controlCode")
    List<AgregatRevue> agregerActifs(UUID entrepriseId);

    /** Constats actifs portant sur un lot d'objets d'un type (une requete pour toute une page : pas de N+1). */
    @Query("select f from Finding f where f.entreprise.id = :entrepriseId and f.sourceType = :entityType and f.sourceId in :entityIds "
            + "and f.status in (com.example.comptable.agent.finding.domain.StatutFinding.OPEN, com.example.comptable.agent.finding.domain.StatutFinding.IN_PROGRESS)")
    List<Finding> findActifsParObjets(UUID entrepriseId, String entityType, Collection<String> entityIds);

    /** Constats actifs (ouverts ou en cours) d'une entreprise : l'espace de revision les range par domaine (peu nombreux par construction : seulement les exceptions). */
    @Query("select f from Finding f where f.entreprise.id = :entrepriseId and f.status in (com.example.comptable.agent.finding.domain.StatutFinding.OPEN, "
            + "com.example.comptable.agent.finding.domain.StatutFinding.IN_PROGRESS)")
    List<Finding> actifsDe(UUID entrepriseId);

    /** Constats resolus (par le systeme ou par un humain) depuis un instant, ecartes exclus. */
    @Query("select count(f) from Finding f where f.entreprise.id = :entrepriseId and f.status = com.example.comptable.agent.finding.domain.StatutFinding.RESOLVED "
            + "and f.resolvedAt >= :depuis")
    long compterResolusDepuis(UUID entrepriseId, java.time.OffsetDateTime depuis);
}
