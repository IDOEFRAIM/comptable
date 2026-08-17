package com.example.comptable.agent.relance.repository;

import com.example.comptable.agent.relance.domain.RelancePieceEnvoyee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface RelancePieceEnvoyeeRepository extends JpaRepository<RelancePieceEnvoyee, UUID> {

    /** Garde anti-spam : vrai si une relance (succes ou echec) existe deja pour cette demande
     * depuis {@code seuil} - evite de renvoyer le meme template si le job est rejoue ou tourne
     * plus d'une fois sur la meme periode. */
    boolean existsByDemandePieceIdAndEnvoyeAtAfter(UUID demandePieceId, OffsetDateTime seuil);

    List<RelancePieceEnvoyee> findByEntrepriseIdOrderByEnvoyeAtDesc(UUID entrepriseId);
}
