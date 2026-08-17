package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.DotationAmortissement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DotationAmortissementRepository extends JpaRepository<DotationAmortissement, UUID> {

    List<DotationAmortissement> findByImmobilisationId(UUID immobilisationId);

    /** Permet a {@code ImmobilisationService.lister} de recuperer les dotations de toutes les
     * immobilisations en une seule requete plutot qu'une par immobilisation (evite un N+1). */
    List<DotationAmortissement> findByImmobilisationIdIn(Collection<UUID> immobilisationIds);

    Optional<DotationAmortissement> findByImmobilisationIdAndAnnee(UUID immobilisationId, Integer annee);

    boolean existsByImmobilisationIdAndAnnee(UUID immobilisationId, Integer annee);
}
