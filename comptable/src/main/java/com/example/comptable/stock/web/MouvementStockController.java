package com.example.comptable.stock.web;

import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.service.MouvementStockService;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
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
@RequestMapping("/api/entreprises/{entrepriseId}/mouvements-stock")
@RequiredArgsConstructor
public class MouvementStockController {

    private final MouvementStockService mouvementStockService;

    @PostMapping
    public ResponseEntity<MouvementStockDto> enregistrer(@PathVariable UUID entrepriseId,
                                                           @Valid @RequestBody CreerMouvementStockRequest request,
                                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        MouvementStockDto mouvement = mouvementStockService.enregistrer(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(mouvement);
    }

    @GetMapping
    public List<MouvementStockDto> lister(@PathVariable UUID entrepriseId, @RequestParam UUID articleId,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return mouvementStockService.lister(entrepriseId, articleId, principal);
    }
}
