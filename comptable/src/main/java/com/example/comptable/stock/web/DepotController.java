package com.example.comptable.stock.web;

import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.service.DepotService;
import com.example.comptable.stock.web.dto.CreerDepotRequest;
import com.example.comptable.stock.web.dto.DepotDto;
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
@RequestMapping("/api/entreprises/{entrepriseId}/depots")
@RequiredArgsConstructor
public class DepotController {

    private final DepotService depotService;

    @PostMapping
    public ResponseEntity<DepotDto> creer(@PathVariable UUID entrepriseId, @Valid @RequestBody CreerDepotRequest request,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(depotService.creer(entrepriseId, request, principal));
    }

    @GetMapping
    public List<DepotDto> lister(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return depotService.lister(entrepriseId, principal);
    }
}
