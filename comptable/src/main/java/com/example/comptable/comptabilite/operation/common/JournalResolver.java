package com.example.comptable.comptabilite.operation.common;

import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.repository.JournalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Resout le journal d'une entreprise par type plutot que par code fixe ("ACH"/"VTE"/...) code en
 * dur - conforme a "ne pas supposer que les codes sont identiques dans toutes les entreprises".
 * Hypothese documentee : {@code EntrepriseService.creerJournauxParDefaut} cree aujourd'hui
 * exactement un journal par TypeJournal et par entreprise, donc le premier trouve suffit ; si cette
 * invariant change un jour, cette classe est le seul endroit a revoir.
 */
@Service
@RequiredArgsConstructor
public class JournalResolver {

    private final JournalRepository journalRepository;

    public Optional<Journal> resoudre(UUID entrepriseId, TypeJournal type) {
        return journalRepository.findByEntrepriseId(entrepriseId).stream()
                .filter(j -> j.getType() == type)
                .findFirst();
    }
}
