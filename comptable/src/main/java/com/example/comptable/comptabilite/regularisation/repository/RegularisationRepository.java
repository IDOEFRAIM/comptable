package com.example.comptable.comptabilite.regularisation.repository;

import com.example.comptable.comptabilite.regularisation.domain.Regularisation;
import com.example.comptable.comptabilite.regularisation.domain.StatutRegularisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RegularisationRepository extends JpaRepository<Regularisation, UUID> {

    List<Regularisation> findByEntrepriseIdOrderByDateOrigineDesc(UUID entrepriseId);

    List<Regularisation> findByEntrepriseIdAndExerciceId(UUID entrepriseId, UUID exerciceId);

    List<Regularisation> findByEntrepriseIdAndExerciceIdAndStatut(UUID entrepriseId, UUID exerciceId, StatutRegularisation statut);
}
