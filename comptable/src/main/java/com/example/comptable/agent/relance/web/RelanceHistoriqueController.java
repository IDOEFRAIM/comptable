package com.example.comptable.agent.relance.web;

import com.example.comptable.agent.relance.RelancePieceService;
import com.example.comptable.agent.relance.web.dto.RelancePieceEnvoyeeDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Historique des relances WhatsApp (Sprint 5, point 4 - tracabilite). La visibilite immediate
 * "un client a ete relance" passe deja par la cloche de notification existante
 * ({@code NotificationService}, un message par relance) ; cet endpoint complete avec le detail
 * horodate par entreprise (succes/echec, numero, identifiant du message WhatsApp) pour un futur
 * écran dedie dans l'onglet "Pièces demandées".
 */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/relances")
@RequiredArgsConstructor
public class RelanceHistoriqueController {

    private final RelancePieceService relancePieceService;

    @GetMapping
    public List<RelancePieceEnvoyeeDto> lister(
            @PathVariable UUID entrepriseId,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return relancePieceService.listerHistorique(entrepriseId, principal);
    }
}
