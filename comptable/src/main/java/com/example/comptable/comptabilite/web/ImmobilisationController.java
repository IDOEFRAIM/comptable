package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.service.ImmobilisationService;
import com.example.comptable.comptabilite.web.dto.ComptabiliserAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.LierAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.MettreEnServiceRequest;
import com.example.comptable.comptabilite.web.dto.SortirImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.GenererDotationsResultDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/immobilisations")
@RequiredArgsConstructor
public class ImmobilisationController {

    private final ImmobilisationService immobilisationService;

    @PostMapping
    public ResponseEntity<ImmobilisationDto> creer(@PathVariable UUID entrepriseId,
                                                     @Valid @RequestBody CreerImmobilisationRequest request,
                                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        ImmobilisationDto immobilisation = immobilisationService.creer(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(immobilisation);
    }

    @GetMapping
    public List<ImmobilisationDto> lister(@PathVariable UUID entrepriseId,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return immobilisationService.lister(entrepriseId, principal);
    }

    @PostMapping("/{immobilisationId}/comptabiliser-acquisition")
    public ImmobilisationDto comptabiliserAcquisition(@PathVariable UUID entrepriseId, @PathVariable UUID immobilisationId,
                                                        @Valid @RequestBody ComptabiliserAcquisitionRequest request,
                                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return immobilisationService.comptabiliserAcquisition(entrepriseId, immobilisationId, request, principal);
    }

    /** Factures d'achat deja comptabilisees auxquelles ce bien peut etre rattache (selecteur, jamais un identifiant saisi). */
    @GetMapping("/{immobilisationId}/candidats-acquisition")
    public java.util.List<com.example.comptable.comptabilite.web.dto.CandidatAcquisitionDto> candidatsAcquisition(
            @PathVariable UUID entrepriseId, @PathVariable UUID immobilisationId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return immobilisationService.candidatsAcquisition(entrepriseId, immobilisationId, principal);
    }

    @PostMapping("/{immobilisationId}/lier-acquisition")
    public ImmobilisationDto lierAcquisition(@PathVariable UUID entrepriseId, @PathVariable UUID immobilisationId,
                                               @Valid @RequestBody LierAcquisitionRequest request,
                                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return immobilisationService.lierAcquisition(entrepriseId, immobilisationId, request, principal);
    }

    @PostMapping("/{immobilisationId}/mise-en-service")
    public ImmobilisationDto mettreEnService(@PathVariable UUID entrepriseId, @PathVariable UUID immobilisationId,
                                               @Valid @RequestBody MettreEnServiceRequest request,
                                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return immobilisationService.mettreEnService(entrepriseId, immobilisationId, request, principal);
    }

    @PostMapping("/{immobilisationId}/sortie")
    public ImmobilisationDto sortir(@PathVariable UUID entrepriseId, @PathVariable UUID immobilisationId,
                                      @Valid @RequestBody SortirImmobilisationRequest request,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return immobilisationService.sortir(entrepriseId, immobilisationId, request, principal);
    }

    @PostMapping("/generer-dotations")
    public GenererDotationsResultDto genererDotations(@PathVariable UUID entrepriseId, @RequestParam int annee,
                                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return immobilisationService.genererDotations(entrepriseId, annee, principal);
    }
}
