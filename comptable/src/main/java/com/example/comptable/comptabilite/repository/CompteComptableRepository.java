package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompteComptableRepository extends JpaRepository<CompteComptable, UUID> {

    List<CompteComptable> findByEntrepriseId(UUID entrepriseId);

    Optional<CompteComptable> findByEntrepriseIdAndNumero(UUID entrepriseId, String numero);

    List<CompteComptable> findByEntrepriseIdAndNumeroStartingWithOrderByNumeroDesc(UUID entrepriseId, String prefixe);

    /** Cle d'idempotence de l'import : un meme compte du referentiel n'est jamais duplique dans
     * le PCE d'une entreprise. */
    Optional<CompteComptable> findByEntrepriseIdAndReferenceId(UUID entrepriseId, Integer referenceId);

    /** Un compte local sans reference est utilisable en detail seulement s'il n'a pas de
     * sous-compte local. */
    boolean existsByParentId(UUID parentId);

    Optional<CompteComptable> findByEntrepriseIdAndReferenceRoleSysteme(UUID entrepriseId, RoleCompteSysteme roleSysteme);
}
