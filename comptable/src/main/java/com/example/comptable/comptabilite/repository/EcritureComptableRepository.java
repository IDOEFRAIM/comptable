package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface EcritureComptableRepository extends JpaRepository<EcritureComptable, UUID> {

    List<EcritureComptable> findByJournalIdAndDateEcritureBetweenOrderByDateEcritureAscNumeroPieceAsc(
            UUID journalId, LocalDate dateDebut, LocalDate dateFin);

    List<EcritureComptable> findByJournalIdAndStatutAndDateEcritureBetweenOrderByDateEcritureAscNumeroPieceAsc(
            UUID journalId, StatutEcriture statut, LocalDate dateDebut, LocalDate dateFin);

    long countByJournalId(UUID journalId);

    long countByEntrepriseIdAndStatut(UUID entrepriseId, StatutEcriture statut);

    /** Ecritures BROUILLON anciennes (mission P0, ControlEngine) : jamais validees depuis
     * longtemps, signal qu'un comptable a probablement oublie de les revoir. */
    List<EcritureComptable> findByEntrepriseIdAndStatutAndDateEcritureBefore(
            UUID entrepriseId, StatutEcriture statut, LocalDate date);

    long countByExercice_IdAndStatut(UUID exerciceId, StatutEcriture statut);

    @Query("select distinct e from EcritureComptable e "
            + "join fetch e.journal "
            + "left join fetch e.lignes l left join fetch l.compte left join fetch l.tiers "
            + "where e.entreprise.id = :entrepriseId "
            + "order by e.dateEcriture desc, e.numeroPiece desc")
    List<EcritureComptable> findByEntrepriseIdFetchAll(UUID entrepriseId);

    /** Ecritures d'un exercice pour l'export FEC (Sprint 10) : seules les ecritures validees sont
     * opposables au fisc, un brouillon ne doit jamais figurer dans un fichier remis a la DGI. */
    @Query("select distinct e from EcritureComptable e "
            + "join fetch e.journal "
            + "left join fetch e.lignes l left join fetch l.compte left join fetch l.tiers "
            + "where e.exercice.id = :exerciceId and e.statut = :statut "
            + "order by e.journal.code asc, e.dateEcriture asc, e.numeroPiece asc")
    List<EcritureComptable> findByExerciceIdAndStatutFetchAll(UUID exerciceId, StatutEcriture statut);

    /** Ecritures (brouillon ou validees) d'un exercice dont le total debit differe du total credit :
     * donnee corrompue, normalement impossible (chaque creation verifie l'equilibre). */
    @Query("select e.id from EcritureComptable e join e.lignes l where e.exercice.id = :exerciceId "
            + "group by e.id having sum(l.debit) <> sum(l.credit)")
    List<UUID> rechercherEcrituresDesequilibrees(UUID exerciceId);

    /** Comptes desactives portant des lignes dans l'exercice : (numero, nombre d'ecritures concernees). */
    @Query("select c.numero, count(distinct e.id) from LigneEcriture l join l.ecriture e join l.compte c "
            + "where e.exercice.id = :exerciceId and c.actif = false group by c.numero order by c.numero")
    List<Object[]> rechercherComptesDesactivesUtilises(UUID exerciceId);
}
