package com.example.comptable.controle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ControlRunRepository extends JpaRepository<ControlRun, UUID> {

    Optional<ControlRun> findFirstByEntrepriseIdOrderByStartedAtDesc(UUID entrepriseId);
}
