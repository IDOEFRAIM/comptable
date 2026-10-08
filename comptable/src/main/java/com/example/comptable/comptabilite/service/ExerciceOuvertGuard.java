package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Regle unique « une ecriture ne peut etre passee que dans un exercice existant ET ouvert » (Phase 1,
 * lot 1.8). Remplace trois copies strictement identiques (meme requete, memes messages, meme code
 * 422) qui vivaient dans {@code AccountingOperationEngine}, {@code EcritureComptableService} et
 * {@code MouvementStockService} ; {@code ComptabilisationService} avait une quatrieme copie, supprimee
 * en passant par le moteur.
 * <p>
 * <p>Atomicite pre-cloture -> cloture (phase F) : la lecture du statut se fait sous verrou PARTAGE, la cloture prend le verrou EXCLUSIF de l'exercice et recalcule
 * ses controles une fois ce verrou obtenu. Il est donc impossible qu'une ecriture soit ajoutee entre la verification et le passage a CLOTURE sans etre vue.</p>
 * <p>Methode statique prenant le repository : les appelants gardent leur propre injection, donc leurs
 * tests unitaires (qui mockent {@code ExerciceComptableRepository}) restent valides tels quels.
 */
public final class ExerciceOuvertGuard {

    private ExerciceOuvertGuard() {
    }

    public static ExerciceComptable exigerOuvert(ExerciceComptableRepository repository, UUID entrepriseId, LocalDate date) {
        ExerciceComptable exercice = repository.findByEntrepriseIdAndDate(entrepriseId, date)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Aucun exercice comptable ne couvre la date " + date));
        // Verrou partage : une cloture en cours (verrou exclusif) nous fait attendre, puis on lit le statut REEL ; une cloture qui debute attend notre commit.
        String statut = repository.statutPartage(exercice.getId()).orElse(exercice.getStatut().name());
        if (!StatutExercice.OUVERT.name().equals(statut)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'exercice couvrant cette date est cloture");
        }
        return exercice;
    }
}
