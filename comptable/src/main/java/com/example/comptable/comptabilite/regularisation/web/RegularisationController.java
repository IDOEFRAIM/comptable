package com.example.comptable.comptabilite.regularisation.web;

import com.example.comptable.comptabilite.operation.common.web.dto.RejeterPropositionRequest;
import com.example.comptable.comptabilite.regularisation.service.RegularisationService;
import com.example.comptable.comptabilite.regularisation.web.dto.CreerRegularisationRequest;
import com.example.comptable.comptabilite.regularisation.web.dto.RegularisationDto;
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
@RequestMapping("/api/entreprises/{entrepriseId}/regularisations")
@RequiredArgsConstructor
public class RegularisationController {

    private final RegularisationService regularisationService;

    @PostMapping
    public ResponseEntity<RegularisationDto> creer(@PathVariable UUID entrepriseId,
            @Valid @RequestBody CreerRegularisationRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(regularisationService.creer(entrepriseId, request, principal));
    }

    @GetMapping
    public List<RegularisationDto> lister(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return regularisationService.lister(entrepriseId, principal);
    }

    @GetMapping("/{regularisationId}")
    public RegularisationDto obtenir(@PathVariable UUID entrepriseId, @PathVariable UUID regularisationId,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return regularisationService.obtenir(entrepriseId, regularisationId, principal);
    }

    @PostMapping("/{regularisationId}/proposition")
    public RegularisationDto proposer(@PathVariable UUID entrepriseId, @PathVariable UUID regularisationId,
                                       @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return regularisationService.proposer(entrepriseId, regularisationId, principal);
    }

    @PostMapping("/{regularisationId}/valider")
    public RegularisationDto valider(@PathVariable UUID entrepriseId, @PathVariable UUID regularisationId,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return regularisationService.valider(entrepriseId, regularisationId, principal);
    }

    @PostMapping("/{regularisationId}/rejeter")
    public RegularisationDto rejeter(@PathVariable UUID entrepriseId, @PathVariable UUID regularisationId,
                                      @Valid @RequestBody RejeterPropositionRequest request,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return regularisationService.rejeter(entrepriseId, regularisationId, request.motif(), principal);
    }

    @PostMapping("/{regularisationId}/reprise")
    public RegularisationDto proposerReprise(@PathVariable UUID entrepriseId, @PathVariable UUID regularisationId,
                                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return regularisationService.proposerReprise(entrepriseId, regularisationId, principal);
    }

    @PostMapping("/{regularisationId}/reprise/valider")
    public RegularisationDto validerReprise(@PathVariable UUID entrepriseId, @PathVariable UUID regularisationId,
                                             @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return regularisationService.validerReprise(entrepriseId, regularisationId, principal);
    }
}
