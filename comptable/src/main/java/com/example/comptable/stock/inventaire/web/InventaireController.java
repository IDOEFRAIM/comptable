package com.example.comptable.stock.inventaire.web;

import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.inventaire.service.InventaireService;
import com.example.comptable.stock.inventaire.web.dto.CreerInventaireRequest;
import com.example.comptable.stock.inventaire.web.dto.InventaireDto;
import com.example.comptable.stock.inventaire.web.dto.SaisirLigneInventaireRequest;
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
@RequestMapping("/api/entreprises/{entrepriseId}/inventaires")
@RequiredArgsConstructor
public class InventaireController {

    private final InventaireService inventaireService;

    @PostMapping
    public ResponseEntity<InventaireDto> creer(@PathVariable UUID entrepriseId, @Valid @RequestBody CreerInventaireRequest request,
                                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventaireService.creer(entrepriseId, request, principal));
    }

    @GetMapping
    public List<InventaireDto> lister(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return inventaireService.lister(entrepriseId, principal);
    }

    @GetMapping("/{inventaireId}")
    public InventaireDto obtenir(@PathVariable UUID entrepriseId, @PathVariable UUID inventaireId,
                                 @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return inventaireService.obtenir(entrepriseId, inventaireId, principal);
    }

    @PostMapping("/{inventaireId}/lignes")
    public InventaireDto saisirLigne(@PathVariable UUID entrepriseId, @PathVariable UUID inventaireId,
                                     @Valid @RequestBody SaisirLigneInventaireRequest request,
                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return inventaireService.saisirLigne(entrepriseId, inventaireId, request, principal);
    }

    @PostMapping("/{inventaireId}/valider")
    public InventaireDto valider(@PathVariable UUID entrepriseId, @PathVariable UUID inventaireId,
                                 @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return inventaireService.valider(entrepriseId, inventaireId, principal);
    }

    @PostMapping("/{inventaireId}/cloturer")
    public InventaireDto cloturer(@PathVariable UUID entrepriseId, @PathVariable UUID inventaireId,
                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return inventaireService.cloturer(entrepriseId, inventaireId, principal);
    }
}
