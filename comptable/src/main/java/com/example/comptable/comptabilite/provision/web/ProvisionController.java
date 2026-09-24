package com.example.comptable.comptabilite.provision.web;

import com.example.comptable.comptabilite.operation.common.web.dto.RejeterPropositionRequest;
import com.example.comptable.comptabilite.provision.service.ProvisionService;
import com.example.comptable.comptabilite.provision.web.dto.CreerProvisionRequest;
import com.example.comptable.comptabilite.provision.web.dto.ProvisionDto;
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
@RequestMapping("/api/entreprises/{entrepriseId}/provisions")
@RequiredArgsConstructor
public class ProvisionController {

    private final ProvisionService provisionService;

    @PostMapping
    public ResponseEntity<ProvisionDto> creer(@PathVariable UUID entrepriseId,
            @Valid @RequestBody CreerProvisionRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(provisionService.creer(entrepriseId, request, principal));
    }

    @GetMapping
    public List<ProvisionDto> lister(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return provisionService.lister(entrepriseId, principal);
    }

    @GetMapping("/{provisionId}")
    public ProvisionDto obtenir(@PathVariable UUID entrepriseId, @PathVariable UUID provisionId,
                                 @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return provisionService.obtenir(entrepriseId, provisionId, principal);
    }

    @PostMapping("/{provisionId}/proposition")
    public ProvisionDto proposer(@PathVariable UUID entrepriseId, @PathVariable UUID provisionId,
                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return provisionService.proposer(entrepriseId, provisionId, principal);
    }

    @PostMapping("/{provisionId}/valider")
    public ProvisionDto valider(@PathVariable UUID entrepriseId, @PathVariable UUID provisionId,
                                 @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return provisionService.valider(entrepriseId, provisionId, principal);
    }

    @PostMapping("/{provisionId}/rejeter")
    public ProvisionDto rejeter(@PathVariable UUID entrepriseId, @PathVariable UUID provisionId,
                                 @Valid @RequestBody RejeterPropositionRequest request,
                                 @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return provisionService.rejeter(entrepriseId, provisionId, request.motif(), principal);
    }
}
