package com.example.comptable.fiscalite.web;

import com.example.comptable.fiscalite.service.DeclarationFiscaleService;
import com.example.comptable.fiscalite.web.dto.CreerDeclarationRequest;
import com.example.comptable.fiscalite.web.dto.DeclarationFiscaleDto;
import com.example.comptable.fiscalite.web.dto.GenererEcheancierRequest;
import com.example.comptable.fiscalite.web.dto.MarquerDeposeeRequest;
import com.example.comptable.fiscalite.web.dto.MarquerPayeeRequest;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/declarations-fiscales")
@RequiredArgsConstructor
public class DeclarationFiscaleController {

    private final DeclarationFiscaleService declarationFiscaleService;

    @GetMapping
    public List<DeclarationFiscaleDto> lister(@PathVariable UUID entrepriseId,
                                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return declarationFiscaleService.lister(entrepriseId, principal);
    }

    @PostMapping
    public ResponseEntity<DeclarationFiscaleDto> creer(@PathVariable UUID entrepriseId,
                                                        @Valid @RequestBody CreerDeclarationRequest request,
                                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        DeclarationFiscaleDto declaration = declarationFiscaleService.creer(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(declaration);
    }

    @PostMapping("/generer-echeancier-tva")
    public List<DeclarationFiscaleDto> genererEcheancierTva(@PathVariable UUID entrepriseId,
                                                             @Valid @RequestBody GenererEcheancierRequest request,
                                                             @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return declarationFiscaleService.genererEcheancierTva(entrepriseId, request.annee(), principal);
    }

    @PostMapping("/{declarationId}/marquer-deposee")
    public DeclarationFiscaleDto marquerDeposee(@PathVariable UUID entrepriseId, @PathVariable UUID declarationId,
                                                 @RequestBody(required = false) MarquerDeposeeRequest request,
                                                 @AuthenticationPrincipal UtilisateurPrincipal principal) {
        LocalDate dateDeclaration = request != null ? request.dateDeclaration() : null;
        return declarationFiscaleService.marquerDeposee(entrepriseId, declarationId, dateDeclaration, principal);
    }

    @PostMapping("/{declarationId}/marquer-payee")
    public DeclarationFiscaleDto marquerPayee(@PathVariable UUID entrepriseId, @PathVariable UUID declarationId,
                                               @RequestBody(required = false) MarquerPayeeRequest request,
                                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        LocalDate datePaiement = request != null ? request.datePaiement() : null;
        BigDecimal montant = request != null ? request.montant() : null;
        return declarationFiscaleService.marquerPayee(entrepriseId, declarationId, datePaiement, montant, principal);
    }

    @PostMapping("/{declarationId}/recalculer-tva")
    public DeclarationFiscaleDto recalculerTva(@PathVariable UUID entrepriseId, @PathVariable UUID declarationId,
                                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return declarationFiscaleService.recalculerMontantTva(entrepriseId, declarationId, principal);
    }
}
