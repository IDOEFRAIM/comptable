package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.service.FactureAvoirService;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.web.dto.CreerFactureAvoirRequest;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureAvoirDto;
import com.example.comptable.comptabilite.web.dto.FactureDto;
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
@RequestMapping("/api/entreprises/{entrepriseId}/factures")
@RequiredArgsConstructor
public class FacturationController {

    private final FacturationService facturationService;
    private final FactureAvoirService factureAvoirService;
    private final com.example.comptable.comptabilite.allocation.PaiementFactureEnrichisseur paiementFactureEnrichisseur;
    private final com.example.comptable.audit.AuditTrailService auditTrailService;

    @PostMapping
    public ResponseEntity<FactureDto> creer(@PathVariable UUID entrepriseId,
                                             @RequestParam TypeFacture type,
                                             @Valid @RequestBody CreerFactureRequest request,
                                             @AuthenticationPrincipal UtilisateurPrincipal principal) {
        FactureDto facture = facturationService.creer(entrepriseId, type, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(facture);
    }

    @GetMapping
    public List<FactureDto> lister(@PathVariable UUID entrepriseId, @RequestParam TypeFacture type,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return paiementFactureEnrichisseur.enrichir(entrepriseId, facturationService.lister(entrepriseId, type, principal));
    }

    @PostMapping("/{factureId}/envoyer")
    public FactureDto envoyer(@PathVariable UUID entrepriseId, @PathVariable UUID factureId,
                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return facturationService.envoyer(entrepriseId, factureId, principal);
    }

    @PostMapping("/{devisId}/accepter")
    public FactureDto accepterDevis(@PathVariable UUID entrepriseId, @PathVariable UUID devisId,
                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return facturationService.accepterDevis(entrepriseId, devisId, principal);
    }

    @PostMapping("/{devisId}/refuser")
    public FactureDto refuserDevis(@PathVariable UUID entrepriseId, @PathVariable UUID devisId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return facturationService.refuserDevis(entrepriseId, devisId, principal);
    }

    @PostMapping("/{devisId}/convertir")
    public ResponseEntity<FactureDto> convertirEnFacture(@PathVariable UUID entrepriseId, @PathVariable UUID devisId,
                                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        FactureDto facture = facturationService.convertirEnFacture(entrepriseId, devisId, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(facture);
    }

    @PostMapping("/{factureId}/valider")
    public FactureDto validerFacture(@PathVariable UUID entrepriseId, @PathVariable UUID factureId,
                                      @RequestParam UUID compteProduitId,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return facturationService.validerFacture(entrepriseId, factureId, compteProduitId, principal);
    }

    @PostMapping("/{factureId}/annuler")
    public FactureDto annuler(@PathVariable UUID entrepriseId, @PathVariable UUID factureId,
                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return facturationService.annulerFacture(entrepriseId, factureId, principal);
    }

    @PostMapping("/{factureId}/creer-avoir")
    public ResponseEntity<FactureAvoirDto> creerAvoir(@PathVariable UUID entrepriseId, @PathVariable UUID factureId,
                                                        @Valid @RequestBody CreerFactureAvoirRequest request,
                                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        FactureAvoirDto avoir = factureAvoirService.creerAvoir(entrepriseId, factureId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(avoir);
    }
}
