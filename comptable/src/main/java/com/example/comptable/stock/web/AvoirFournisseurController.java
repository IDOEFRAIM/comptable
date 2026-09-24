package com.example.comptable.stock.web;

import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.service.AvoirFournisseurService;
import com.example.comptable.stock.web.dto.AvoirFournisseurDto;
import com.example.comptable.stock.web.dto.CreerAvoirFournisseurRequest;
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
@RequestMapping("/api/entreprises/{entrepriseId}/avoirs-fournisseur")
@RequiredArgsConstructor
public class AvoirFournisseurController {

    private final AvoirFournisseurService avoirFournisseurService;

    @PostMapping("/factures/{factureId}")
    public ResponseEntity<AvoirFournisseurDto> creer(@PathVariable UUID entrepriseId, @PathVariable UUID factureId,
            @RequestBody CreerAvoirFournisseurRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        AvoirFournisseurDto avoir = avoirFournisseurService.creer(entrepriseId, factureId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(avoir);
    }

    @GetMapping
    public List<AvoirFournisseurDto> lister(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return avoirFournisseurService.lister(entrepriseId, principal);
    }

    @GetMapping("/{avoirId}")
    public AvoirFournisseurDto obtenir(@PathVariable UUID entrepriseId, @PathVariable UUID avoirId,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return avoirFournisseurService.obtenir(entrepriseId, avoirId, principal);
    }
}
