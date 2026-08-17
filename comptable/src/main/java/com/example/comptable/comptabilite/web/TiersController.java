package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.CreerTiersRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
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
@RequestMapping("/api/entreprises/{entrepriseId}/tiers")
@RequiredArgsConstructor
public class TiersController {

    private final TiersService tiersService;

    @PostMapping
    public ResponseEntity<TiersDto> creer(@PathVariable UUID entrepriseId,
                                           @Valid @RequestBody CreerTiersRequest request,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        TiersDto tiers = tiersService.creer(entrepriseId, request.type(), request.nom(), request.numeroContribuable(), principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(tiers);
    }

    @GetMapping
    public List<TiersDto> lister(@PathVariable UUID entrepriseId,
                                  @RequestParam(required = false) TypeTiers type,
                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return tiersService.lister(entrepriseId, type, principal);
    }
}
