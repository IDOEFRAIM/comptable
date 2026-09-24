package com.example.comptable.comptabilite.depreciation.web;

import com.example.comptable.comptabilite.depreciation.service.DepreciationService;
import com.example.comptable.comptabilite.depreciation.web.dto.CreerDepreciationRequest;
import com.example.comptable.comptabilite.depreciation.web.dto.DepreciationDto;
import com.example.comptable.comptabilite.operation.common.web.dto.RejeterPropositionRequest;
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
@RequestMapping("/api/entreprises/{entrepriseId}/depreciations")
@RequiredArgsConstructor
public class DepreciationController {

    private final DepreciationService depreciationService;

    @PostMapping
    public ResponseEntity<DepreciationDto> creer(@PathVariable UUID entrepriseId,
            @Valid @RequestBody CreerDepreciationRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(depreciationService.creer(entrepriseId, request, principal));
    }

    @GetMapping
    public List<DepreciationDto> lister(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return depreciationService.lister(entrepriseId, principal);
    }

    @GetMapping("/{depreciationId}")
    public DepreciationDto obtenir(@PathVariable UUID entrepriseId, @PathVariable UUID depreciationId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return depreciationService.obtenir(entrepriseId, depreciationId, principal);
    }

    @PostMapping("/{depreciationId}/proposition")
    public DepreciationDto proposer(@PathVariable UUID entrepriseId, @PathVariable UUID depreciationId,
                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return depreciationService.proposer(entrepriseId, depreciationId, principal);
    }

    @PostMapping("/{depreciationId}/valider")
    public DepreciationDto valider(@PathVariable UUID entrepriseId, @PathVariable UUID depreciationId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return depreciationService.valider(entrepriseId, depreciationId, principal);
    }

    @PostMapping("/{depreciationId}/rejeter")
    public DepreciationDto rejeter(@PathVariable UUID entrepriseId, @PathVariable UUID depreciationId,
                                    @Valid @RequestBody RejeterPropositionRequest request,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return depreciationService.rejeter(entrepriseId, depreciationId, request.motif(), principal);
    }
}
