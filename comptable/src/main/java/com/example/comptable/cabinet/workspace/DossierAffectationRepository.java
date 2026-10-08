package com.example.comptable.cabinet.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DossierAffectationRepository extends JpaRepository<DossierAffectation, UUID> {

    Optional<DossierAffectation> findByEntrepriseIdAndExerciceId(UUID entrepriseId, UUID exerciceId);
}
