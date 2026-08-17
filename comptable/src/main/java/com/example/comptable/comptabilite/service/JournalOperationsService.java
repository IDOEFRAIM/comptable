package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.example.comptable.comptabilite.domain.StatutEcriture.VALIDEE;

/** Journal des operations : ecritures validees d'un journal sur une periode, chronologiques. */
@Service
@RequiredArgsConstructor
public class JournalOperationsService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final JournalRepository journalRepository;
    private final EcritureComptableRepository ecritureComptableRepository;

    @Transactional(readOnly = true)
    public List<EcritureComptableDto> generer(UUID entrepriseId, UUID journalId, LocalDate dateDebut, LocalDate dateFin,
                                               UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        Journal journal = journalRepository.findById(journalId)
                .filter(j -> j.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal introuvable"));

        List<EcritureComptable> ecritures = ecritureComptableRepository
                .findByJournalIdAndStatutAndDateEcritureBetweenOrderByDateEcritureAscNumeroPieceAsc(
                        journal.getId(), VALIDEE, dateDebut, dateFin);

        return ecritures.stream().map(EcritureComptableDto::from).toList();
    }
}
