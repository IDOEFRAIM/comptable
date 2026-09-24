package com.example.comptable.comptabilite.provision.repository;

import com.example.comptable.comptabilite.provision.domain.Provision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProvisionRepository extends JpaRepository<Provision, UUID> {

    List<Provision> findByEntrepriseIdOrderByCreatedAtDesc(UUID entrepriseId);
}
