package com.example.comptable.demande.web;

import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.service.DemandePieceService;
import com.example.comptable.demande.web.dto.CreerDemandePieceRequest;
import com.example.comptable.demande.web.dto.DemandePieceClientDto;
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
    public List<?> lister(@PathVariable UUID entrepriseId,
                          @RequestParam(required = false) StatutDemandePiece statut,
                          @AuthenticationPrincipal UtilisateurPrincipal principal) {
        boolean cabinet = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_COLLABORATEUR") || a.getAuthority().equals("ROLE_EXPERT_COMPTABLE"));
        // Le CLIENT ne voit que de quoi fournir sa piece : jamais d'identifiant de constat/ecriture, de motif ni d'etat d'envoi.
        if (!cabinet) {
            return demandePieceService.listerPourClient(entrepriseId, statut, principal);
        }
        return demandePieceService.lister(entrepriseId, statut, principal);
    }

    @PostMapping
    public ResponseEntity<DemandePieceDto> creer(@PathVariable UUID entrepriseId,
                                                  @Valid @RequestBody CreerDemandePieceRequest request,
                                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        DemandePieceDto demande = demandePieceService.creer(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(demande);
    }

    /** Demande le justificatif d'une operation signalee par un constat « piece manquante ». */
    @PostMapping("/depuis-constat/{findingId}")
    public ResponseEntity<DemandePieceDto> demanderPourConstat(@PathVariable UUID entrepriseId, @PathVariable UUID findingId,
                                                               @RequestBody(required = false) com.example.comptable.demande.web.dto.DemanderPieceRequest request,
                                                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(demandePieceService.demanderPourConstat(entrepriseId, findingId, request, principal));
    }

    @PostMapping("/{demandeId}/annuler")
    public DemandePieceDto annuler(@PathVariable UUID entrepriseId, @PathVariable UUID demandeId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return demandePieceService.annuler(entrepriseId, demandeId, principal);
    }

    @PostMapping("/{demandeId}/repondre")
    public Object repondre(@PathVariable UUID entrepriseId, @PathVariable UUID demandeId,
                           @RequestParam TypeDocument typeDocument,
                           @RequestParam MultipartFile fichier,
                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        DemandePieceDto reponse = demandePieceService.repondre(entrepriseId, demandeId, typeDocument, fichier, principal);
        boolean cabinet = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_COLLABORATEUR") || a.getAuthority().equals("ROLE_EXPERT_COMPTABLE"));
        // Le CLIENT ne recoit jamais d'identifiant de constat/ecriture ni d'etat interne, meme en reponse a son propre depot.
        return cabinet ? reponse : DemandePieceClientDto.de(reponse);
    }
}
