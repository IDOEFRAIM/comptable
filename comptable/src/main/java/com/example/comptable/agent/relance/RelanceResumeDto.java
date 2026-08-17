package com.example.comptable.agent.relance;

/** Resultat d'une execution du batch de relance, journalise par {@code RelanceScheduler}. */
public record RelanceResumeDto(int envoyees, int echecs, int ignorees) {
}
