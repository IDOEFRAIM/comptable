package com.example.comptable.demande.web;

import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.service.DemandePieceService;
import com.example.comptable.demande.web.dto.CreerDemandePieceRequest;
import com.example.comptable.demande.web.dto.DemandePieceDto;
import com.example.comptable.document.domain.TypeDocument;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/demandes-pieces")
@RequiredArgsConstructor
public class DemandePieceController {

    private final DemandePieceService demandePieceService;

    @GetMapping
    public List<DemandePieceDto> lister(@PathVariable UUID entrepriseId,
                                         @RequestParam(required = false) StatutDemandePiece statut,
                                         @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return demandePieceService.lister(entrepriseId, statut, principal);
    }

    @PostMapping
    public ResponseEntity<DemandePieceDto> creer(@PathVariable UUID entrepriseId,
                                                  @Valid @RequestBody CreerDemandePieceRequest request,
                                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        DemandePieceDto demande = demandePieceService.creer(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(demande);
    }

    @PostMapping("/{demandeId}/annuler")
    public DemandePieceDto annuler(@PathVariable UUID entrepriseId, @PathVariable UUID demandeId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return demandePieceService.annuler(entrepriseId, demandeId, principal);
    }

    @PostMapping("/{demandeId}/repondre")
    public DemandePieceDto repondre(@PathVariable UUID entrepriseId, @PathVariable UUID demandeId,
                                     @RequestParam TypeDocument typeDocument,
                                     @RequestParam MultipartFile fichier,
                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return demandePieceService.repondre(entrepriseId, demandeId, typeDocument, fichier, principal);
    }
}
