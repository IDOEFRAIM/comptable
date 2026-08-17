package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.service.ComptabilisationService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}")
@RequiredArgsConstructor
public class EcritureController {

    private final ComptabilisationService comptabilisationService;
    private final EcritureComptableService ecritureComptableService;

    @GetMapping("/ecritures")
    public List<EcritureComptableDto> lister(@PathVariable UUID entrepriseId,
                                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ecritureComptableService.lister(entrepriseId, principal);
    }

    @PostMapping("/ecritures")
    public ResponseEntity<EcritureComptableDto> creer(@PathVariable UUID entrepriseId,
                                                        @Valid @RequestBody CreerEcritureRequest request,
                                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        EcritureComptableDto ecriture = ecritureComptableService.creerManuelle(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ecriture);
    }

    @PostMapping("/documents/{documentId}/comptabiliser")
    public ResponseEntity<EcritureComptableDto> comptabiliser(
            @PathVariable UUID entrepriseId,
            @PathVariable UUID documentId,
            @Valid @RequestBody ComptabiliserDocumentRequest request,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entrepriseId, documentId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ecriture);
    }

    @GetMapping("/ecritures/{ecritureId}")
    public EcritureComptableDto obtenir(@PathVariable UUID entrepriseId, @PathVariable UUID ecritureId,
                                         @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ecritureComptableService.obtenir(entrepriseId, ecritureId, principal);
    }

    @PostMapping("/ecritures/{ecritureId}/valider")
    public EcritureComptableDto valider(@PathVariable UUID entrepriseId, @PathVariable UUID ecritureId,
                                         @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ecritureComptableService.valider(entrepriseId, ecritureId, principal);
    }

    @DeleteMapping("/ecritures/{ecritureId}")
    public ResponseEntity<Void> supprimer(@PathVariable UUID entrepriseId, @PathVariable UUID ecritureId,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        ecritureComptableService.supprimer(entrepriseId, ecritureId, principal);
        return ResponseEntity.noContent().build();
    }
}
