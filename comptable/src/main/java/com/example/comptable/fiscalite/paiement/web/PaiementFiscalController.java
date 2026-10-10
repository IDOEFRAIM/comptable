package com.example.comptable.fiscalite.paiement.web;

import com.example.comptable.fiscalite.paiement.service.PaiementFiscalService;
import com.example.comptable.fiscalite.paiement.web.dto.InitierPaiementRequest;
import com.example.comptable.fiscalite.paiement.web.dto.PaiementFiscalDto;
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
@RequestMapping("/api/entreprises/{entrepriseId}/declarations-fiscales/{declarationId}/paiements")
@RequiredArgsConstructor
public class PaiementFiscalController {

    private final PaiementFiscalService paiementFiscalService;
    private final com.example.comptable.fiscalite.paiement.PaiementEnLigneGarde garde;

    @GetMapping
    public List<PaiementFiscalDto> lister(@PathVariable UUID entrepriseId, @PathVariable UUID declarationId,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return paiementFiscalService.lister(entrepriseId, declarationId, principal);
    }

    @PostMapping
    public ResponseEntity<PaiementFiscalDto> initier(@PathVariable UUID entrepriseId, @PathVariable UUID declarationId,
                                                       @Valid @RequestBody InitierPaiementRequest request,
                                                       @AuthenticationPrincipal UtilisateurPrincipal principal) {
        garde.exigerInitiationPossible();
        PaiementFiscalDto paiement = paiementFiscalService.initierPaiement(entrepriseId, declarationId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(paiement);
    }

    /**
     * Reservee au mode simulation (voir {@code esyntaxe.mode} / {@code fasoarzeka.mode}) : confirme
     * "a la main" un paiement en attente, en l'absence de webhook reel des fournisseurs. A retirer -
     * ou a proteger derriere un role dedie - une fois les vraies integrations en production.
     */
    @PostMapping("/{paiementId}/simuler-confirmation")
    public PaiementFiscalDto simulerConfirmation(@PathVariable UUID entrepriseId, @PathVariable UUID declarationId,
                                                  @PathVariable UUID paiementId,
                                                  @RequestBody(required = false) SimulerConfirmationRequest request,
                                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        garde.exigerHorsProduction();
        boolean reussite = request == null || request.reussite();
        return paiementFiscalService.confirmerParId(entrepriseId, declarationId, paiementId, reussite, principal);
    }

    public record SimulerConfirmationRequest(boolean reussite) {
    }
}
