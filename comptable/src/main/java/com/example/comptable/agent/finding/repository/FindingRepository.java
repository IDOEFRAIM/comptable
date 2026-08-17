package com.example.comptable.agent.finding.repository;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

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
}
