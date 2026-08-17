package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.StatutImmobilisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImmobilisationRepository extends JpaRepository<Immobilisation, UUID> {

    List<Immobilisation> findByEntrepriseIdOrderByDateAcquisitionAsc(UUID entrepriseId);

    List<Immobilisation> findByEntrepriseIdAndStatut(UUID entrepriseId, StatutImmobilisation statut);
}
