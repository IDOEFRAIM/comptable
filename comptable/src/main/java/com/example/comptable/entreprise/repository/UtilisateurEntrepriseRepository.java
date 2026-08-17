package com.example.comptable.entreprise.repository;

import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.entreprise.domain.UtilisateurEntrepriseId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UtilisateurEntrepriseRepository extends JpaRepository<UtilisateurEntreprise, UtilisateurEntrepriseId> {

    boolean existsByIdUtilisateurIdAndIdEntrepriseId(UUID utilisateurId, UUID entrepriseId);

    List<UtilisateurEntreprise> findByIdUtilisateurId(UUID utilisateurId);

    /** Sens inverse de {@link #findByIdUtilisateurId} : tous les clients rattaches a un lot
     * d'entreprises, en une seule requete (utilise par RelancePieceService pour eviter un N+1
     * lors du traitement de plusieurs demandes en retard). */
    List<UtilisateurEntreprise> findByIdEntrepriseIdIn(Collection<UUID> entrepriseIds);
}
