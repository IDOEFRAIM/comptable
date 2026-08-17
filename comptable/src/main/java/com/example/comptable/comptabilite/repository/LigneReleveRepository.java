package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.LigneReleve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface LigneReleveRepository extends JpaRepository<LigneReleve, UUID> {

    /** Lignes d'ecriture deja pointees par un releve quelconque de ce compte, pour eviter un double appariement. */
    @Query("select l.ligneEcritureId from LigneReleve l "
            + "where l.ligneEcritureId is not null and l.releve.compte.id = :compteId")
    List<UUID> rechercherLigneEcritureIdsPointees(UUID compteId);
}
