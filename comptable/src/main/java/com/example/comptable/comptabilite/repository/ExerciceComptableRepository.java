package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.ExerciceComptable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExerciceComptableRepository extends JpaRepository<ExerciceComptable, UUID> {

    List<ExerciceComptable> findByEntrepriseId(UUID entrepriseId);

    @org.springframework.data.jpa.repository.Query(
            "select e from ExerciceComptable e where e.entreprise.id = :entrepriseId "
                    + "and :date between e.dateDebut and e.dateFin")
    Optional<ExerciceComptable> findByEntrepriseIdAndDate(UUID entrepriseId, LocalDate date);
}
