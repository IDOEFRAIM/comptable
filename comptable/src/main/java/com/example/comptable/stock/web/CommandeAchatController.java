package com.example.comptable.stock.web;

import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.service.CommandeAchatService;
import com.example.comptable.stock.web.dto.CommandeAchatDto;
import com.example.comptable.stock.web.dto.CreerCommandeAchatRequest;
import com.example.comptable.stock.web.dto.FactureFournisseurDto;
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
@RequestMapping("/api/entreprises/{entrepriseId}/commandes-achat")
@RequiredArgsConstructor
public class CommandeAchatController {

    private final CommandeAchatService commandeAchatService;

    @PostMapping
    public ResponseEntity<CommandeAchatDto> creer(@PathVariable UUID entrepriseId, @Valid @RequestBody CreerCommandeAchatRequest request,
                                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        CommandeAchatDto commande = commandeAchatService.creer(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(commande);
    }

    @GetMapping
    public List<CommandeAchatDto> lister(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return commandeAchatService.lister(entrepriseId, principal);
    }

    @PostMapping("/{commandeId}/valider-reception")
    public CommandeAchatDto validerReception(@PathVariable UUID entrepriseId, @PathVariable UUID commandeId,
                                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return commandeAchatService.validerReception(entrepriseId, commandeId, principal);
    }

    @PostMapping("/{commandeId}/generer-facture-fournisseur")
    public ResponseEntity<FactureFournisseurDto> genererFactureFournisseur(
            @PathVariable UUID entrepriseId, @PathVariable UUID commandeId,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        FactureFournisseurDto facture = commandeAchatService.genererFactureFournisseur(entrepriseId, commandeId, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(facture);
    }
}
