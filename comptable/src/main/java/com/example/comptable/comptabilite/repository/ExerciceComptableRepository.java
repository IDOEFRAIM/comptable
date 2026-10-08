package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.ExerciceComptable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExerciceComptableRepository extends JpaRepository<ExerciceComptable, UUID> {

    List<ExerciceComptable> findByEntrepriseId(UUID entrepriseId);

    /** Exercice precedent REEL (celui dont la date de fin precede le plus recemment la date de debut donnee) : exercices irreguliers ou de duree quelconque inclus. */
    Optional<ExerciceComptable> findFirstByEntrepriseIdAndDateFinLessThanOrderByDateFinDesc(UUID entrepriseId, LocalDate dateDebut);

    @org.springframework.data.jpa.repository.Query(
            "select e from ExerciceComptable e where e.entreprise.id = :entrepriseId "
                    + "and :date between e.dateDebut and e.dateFin")
    Optional<ExerciceComptable> findByEntrepriseIdAndDate(UUID entrepriseId, LocalDate date);

    /** Verrou d'ecriture sur l'exercice : deux clotures simultanees se serialisent (la seconde voit l'exercice deja cloture). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from ExerciceComptable e where e.id = :id")
    Optional<ExerciceComptable> verrouiller(UUID id);

    /**
     * Statut ACTUEL de l'exercice, lu avec un verrou PARTAGE (SELECT ... FOR SHARE) : toute ecriture se passe sous ce verrou, la cloture prend le verrou EXCLUSIF.
     * Une cloture attend donc les ecritures en cours, et une ecriture qui arrive pendant la cloture attend qu'elle soit terminee puis voit l'exercice cloture.
     * Scalaire natif : on lit la ligne en base, jamais l'entite deja en cache.
     */
    @org.springframework.data.jpa.repository.Query(value = "select statut from exercice_comptable where id = :id for share", nativeQuery = true)
    Optional<String> statutPartage(UUID id);
}
