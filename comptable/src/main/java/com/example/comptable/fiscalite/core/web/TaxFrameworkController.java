package com.example.comptable.fiscalite.core.web;

import com.example.comptable.fiscalite.core.TaxFrameworkService;
import com.example.comptable.fiscalite.core.TaxFrameworkService.CreateRequest;
import com.example.comptable.fiscalite.core.TaxFrameworkService.TaxFramework;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Cadres fiscaux du cabinet (créés DRAFT, vérifiés avec leurs sources par un expert-comptable). Sous {@code /api/cabinet/**} : jamais accessible à un CLIENT. */
@RestController
@RequestMapping("/api/cabinet/fiscal/cadres")
@RequiredArgsConstructor
public class TaxFrameworkController {

    private final TaxFrameworkService service;

    @GetMapping
    public List<TaxFramework> lister(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.lister(principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaxFramework creer(@RequestBody CreateRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.creer(principal, request);
    }

    @PostMapping("/{id}/verifier")
    public TaxFramework verifier(@PathVariable UUID id, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.verifier(principal, id);
    }

    @PostMapping("/{id}/retirer")
    public TaxFramework retirer(@PathVariable UUID id, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.retirer(principal, id);
    }
}
