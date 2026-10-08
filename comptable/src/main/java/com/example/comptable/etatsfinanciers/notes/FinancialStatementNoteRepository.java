package com.example.comptable.etatsfinanciers.notes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FinancialStatementNoteRepository extends JpaRepository<FinancialStatementNote, UUID> {

    /** Toutes les notes d'un exercice pour une version de definition : une seule requete, jamais une requete par note. */
    List<FinancialStatementNote> findByEntrepriseIdAndExerciceIdAndFrameworkAndDefinitionVersion(UUID entrepriseId, UUID exerciceId, String framework, String definitionVersion);
}
