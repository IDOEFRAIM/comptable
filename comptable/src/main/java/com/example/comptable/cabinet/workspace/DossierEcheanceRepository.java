package com.example.comptable.cabinet.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DossierEcheanceRepository extends JpaRepository<DossierEcheance, UUID> {

    /** Toutes les échéances ouvertes du cabinet, en une requête : l'appariement aux dossiers se fait en mémoire. */
    List<DossierEcheance> findByTenantIdAndStatutOrderByDateEcheanceAsc(UUID tenantId, DossierEcheance.Statut statut);

    Optional<DossierEcheance> findByIdAndTenantId(UUID id, UUID tenantId);
}
