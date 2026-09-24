package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.PlanComptableReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanComptableReferenceRepository extends JpaRepository<PlanComptableReference, Integer> {

    List<PlanComptableReference> findByActifTrue();

    Optional<PlanComptableReference> findByNumero(String numero);

    List<PlanComptableReference> findByClasse(Short classe);

    List<PlanComptableReference> findByParentId(Integer parentId);
}
