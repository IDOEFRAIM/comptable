package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.service.PlanComptableImportService;
import com.example.comptable.comptabilite.service.PlanComptableService;
import com.example.comptable.comptabilite.web.dto.CompteComptableArbreDto;
import com.example.comptable.comptabilite.web.dto.CompteComptableDto;
import com.example.comptable.comptabilite.web.dto.CreerSubdivisionCompteRequest;
import com.example.comptable.comptabilite.web.dto.ImporterReferenceRequest;
import com.example.comptable.comptabilite.web.dto.ImporterReferenceResultDto;
import com.example.comptable.comptabilite.web.dto.ModifierCompteRequest;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Plan comptable enrichi (hierarchie, import idempotent, subdivisions locales). Coexiste avec
 * la route legacy {@code /api/entreprises/{id}/comptes} ({@link ReferentielController}), laissee
 * intacte pour ne casser ni le frontend existant ni les consommateurs deja en place. */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/plan-comptable")
@RequiredArgsConstructor
public class PlanComptableController {

    private final PlanComptableService planComptableService;
    private final PlanComptableImportService planComptableImportService;

    @GetMapping
    public List<CompteComptableDto> lister(@PathVariable UUID entrepriseId,
                                            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return planComptableService.lister(entrepriseId, principal);
    }

    @GetMapping("/arbre")
    public List<CompteComptableArbreDto> arbre(@PathVariable UUID entrepriseId,
                                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return planComptableService.arbre(entrepriseId, principal);
    }

    @PostMapping("/importer-reference")
    public ImporterReferenceResultDto importerReference(@PathVariable UUID entrepriseId,
                                                          @Valid @RequestBody ImporterReferenceRequest request,
                                                          @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return planComptableImportService.importer(entrepriseId, request, principal);
    }

    @PostMapping("/comptes")
    public ResponseEntity<CompteComptableDto> creerSubdivision(@PathVariable UUID entrepriseId,
                                                                @Valid @RequestBody CreerSubdivisionCompteRequest request,
                                                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        CompteComptableDto compte = planComptableService.creerSubdivision(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(compte);
    }

    @PutMapping("/comptes/{compteId}")
    public CompteComptableDto modifierCompte(@PathVariable UUID entrepriseId, @PathVariable UUID compteId,
                                              @RequestBody ModifierCompteRequest request,
                                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return planComptableService.modifier(entrepriseId, compteId, request, principal);
    }

    @DeleteMapping("/comptes/{compteId}")
    public ResponseEntity<Void> supprimerCompte(@PathVariable UUID entrepriseId, @PathVariable UUID compteId,
                                                 @AuthenticationPrincipal UtilisateurPrincipal principal) {
        planComptableService.supprimer(entrepriseId, compteId, principal);
        return ResponseEntity.noContent().build();
    }
}
