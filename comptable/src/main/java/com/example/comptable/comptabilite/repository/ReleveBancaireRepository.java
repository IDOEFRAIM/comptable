package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.ReleveBancaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ReleveBancaireRepository extends JpaRepository<ReleveBancaire, UUID> {

    List<ReleveBancaire> findByEntrepriseIdOrderByDateFinDesc(UUID entrepriseId);

    /** Releves (avec compte et lignes, une seule requete) ayant au moins une ligne non pointee : base de la revue bancaire. */
    @Query("select distinct r from ReleveBancaire r join fetch r.compte left join fetch r.lignes "
            + "where r.entreprise.id = :entrepriseId and exists "
            + "(select 1 from LigneReleve l where l.releve = r and l.ligneEcritureId is null) order by r.dateFin desc")
    List<ReleveBancaire> rechercherAvecLignesNonPointees(UUID entrepriseId);
}
