package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.ReferentielComptableService;
import com.example.comptable.comptabilite.web.dto.CompteComptableDto;
import com.example.comptable.comptabilite.web.dto.CreerCompteRequest;
import com.example.comptable.comptabilite.web.dto.ExerciceComptableDto;
import com.example.comptable.comptabilite.web.dto.JournalDto;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}")
@RequiredArgsConstructor
public class ReferentielController {

    private final ReferentielComptableService referentielComptableService;
    private final ClotureExerciceService clotureExerciceService;

    @GetMapping("/comptes")
    public List<CompteComptableDto> listerComptes(@PathVariable UUID entrepriseId,
                                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return referentielComptableService.listerComptes(entrepriseId, principal);
    }

    /** Ajout d'un compte general personnalise ; le plan SYSCOHADA clone a la creation de
     * l'entreprise reste lui inaccessible en ecriture (aucun endpoint ne le modifie). */
    @PostMapping("/comptes")
    public ResponseEntity<CompteComptableDto> creerCompte(@PathVariable UUID entrepriseId,
                                                            @Valid @RequestBody CreerCompteRequest request,
                                                            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        CompteComptableDto compte = referentielComptableService.creerCompte(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(compte);
    }

    @GetMapping("/journaux")
    public List<JournalDto> listerJournaux(@PathVariable UUID entrepriseId,
                                            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return referentielComptableService.listerJournaux(entrepriseId, principal);
    }

    @GetMapping("/exercices")
    public List<ExerciceComptableDto> listerExercices(@PathVariable UUID entrepriseId,
                                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return referentielComptableService.listerExercices(entrepriseId, principal);
    }

    @PostMapping("/exercices/{exerciceId}/cloturer")
    public ExerciceComptableDto cloturerExercice(@PathVariable UUID entrepriseId, @PathVariable UUID exerciceId,
                                                  @RequestBody(required = false) ClotureExerciceService.ConfirmationCloture confirmation,
                                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return clotureExerciceService.cloturerAvecConfirmation(entrepriseId, exerciceId, principal, confirmation);
    }
}
