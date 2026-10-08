package com.example.comptable.comptabilite.balance;

import com.example.comptable.comptabilite.domain.ExerciceComptable;

import java.util.List;
import java.util.Optional;

/** Soldes de l'exercice N et, s'il existe, de l'exercice precedent REEL (N-1), calcules dans la meme vue. */
public record ExerciseBalances(ExerciceComptable exercice, List<AccountBalance> current,
                               Optional<ExerciceComptable> previousExercice, List<AccountBalance> previous) {
}
