package com.example.comptable.entreprise.web;

import com.example.comptable.entreprise.service.EntrepriseService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.entreprise.web.dto.FicheEntreprise;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises")
@RequiredArgsConstructor
public class EntrepriseController {

    private final EntrepriseService entrepriseService;

    @PostMapping
    public ResponseEntity<EntrepriseDto> creer(@Valid @RequestBody CreerEntrepriseRequest request,
                                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        EntrepriseDto entreprise = entrepriseService.creer(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(entreprise);
    }

    @GetMapping
    public List<EntrepriseDto> lister(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return entrepriseService.lister(principal);
    }

    /** Fiche légale (IFU, RCCM, adresse…) : ce qui figure sur les devis et factures. */
    @GetMapping("/{entrepriseId}/fiche")
    public FicheEntreprise fiche(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return entrepriseService.fiche(entrepriseId, principal);
    }

    @PutMapping("/{entrepriseId}/fiche")
    public FicheEntreprise modifierFiche(@PathVariable UUID entrepriseId, @Valid @RequestBody FicheEntreprise fiche, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return entrepriseService.modifierFiche(entrepriseId, fiche, principal);
    }
}
