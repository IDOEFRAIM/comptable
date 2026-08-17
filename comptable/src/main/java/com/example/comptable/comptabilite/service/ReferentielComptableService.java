package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.web.dto.CompteComptableDto;
import com.example.comptable.comptabilite.web.dto.CreerCompteRequest;
import com.example.comptable.comptabilite.web.dto.ExerciceComptableDto;
import com.example.comptable.comptabilite.web.dto.JournalDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Expose le plan comptable, les journaux et les exercices d'une entreprise (listes utilisees par les formulaires). */
@Service
@RequiredArgsConstructor
public class ReferentielComptableService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final CompteComptableRepository compteComptableRepository;
    private final JournalRepository journalRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;

    @Transactional(readOnly = true)
    public List<CompteComptableDto> listerComptes(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return compteComptableRepository.findByEntrepriseId(entrepriseId).stream()
                .map(CompteComptableDto::from)
                .sorted((a, b) -> a.numero().compareTo(b.numero()))
                .toList();
    }

    /**
     * Ajoute un compte general personnalise (ex: subdivision analytique non prevue par le
     * referentiel SYSCOHADA). Le plan comptable general lui-meme reste en lecture seule : cette
     * methode ne modifie jamais {@code PlanComptableReference} ni les comptes deja clones depuis
     * ce referentiel, elle insere seulement une ligne {@code CompteComptable} supplementaire sans
     * reference (meme principe que les sous-comptes auxiliaires de {@code TiersService}).
     */
    @Transactional
    public CompteComptableDto creerCompte(UUID entrepriseId, CreerCompteRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, request.numero()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un compte porte deja ce numero");
        }

        CompteComptable compte = new CompteComptable();
        compte.setEntreprise(entreprise);
        compte.setNumero(request.numero());
        compte.setIntitule(request.intitule());
        compte.setLettrable(request.lettrable());
        return CompteComptableDto.from(compteComptableRepository.save(compte));
    }

    @Transactional(readOnly = true)
    public List<JournalDto> listerJournaux(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return journalRepository.findByEntrepriseId(entrepriseId).stream()
                .map(JournalDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExerciceComptableDto> listerExercices(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return exerciceComptableRepository.findByEntrepriseId(entrepriseId).stream()
                .map(ExerciceComptableDto::from)
                .sorted(Comparator.comparing(ExerciceComptableDto::dateDebut).reversed())
                .toList();
    }
}
