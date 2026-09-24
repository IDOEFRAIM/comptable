package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Numero de piece par defaut pour une ecriture generee automatiquement : {@code
 * {code-journal}-{annee}-{sequence:04d}}. Centralise ici le corps auparavant duplique a
 * l'identique dans EcritureComptableService, FacturationService et FactureAvoirService.
 * ClotureExerciceService/ImmobilisationService/CommandeAchatService gardent leur propre copie
 * (hors perimetre de cette mission - voir le resume de mission pour cette dette documentee).
 */
@Component
@RequiredArgsConstructor
public class NumeroPieceGenerator {

    private final EcritureComptableRepository ecritureComptableRepository;

    public String genererNumeroPiece(Journal journal) {
        long sequence = ecritureComptableRepository.countByJournalId(journal.getId()) + 1;
        return "%s-%d-%04d".formatted(journal.getCode(), LocalDate.now().getYear(), sequence);
    }
}
