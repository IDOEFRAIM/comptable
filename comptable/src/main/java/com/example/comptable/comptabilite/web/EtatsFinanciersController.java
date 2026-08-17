package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.service.EtatsFinanciersService;
import com.example.comptable.comptabilite.web.dto.BilanDto;
import com.example.comptable.comptabilite.web.dto.CompteResultatDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/etats-financiers")
@RequiredArgsConstructor
public class EtatsFinanciersController {

    private final EtatsFinanciersService etatsFinanciersService;

    @GetMapping("/bilan")
    public BilanDto bilan(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId,
                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return etatsFinanciersService.genererBilan(entrepriseId, exerciceId, principal);
    }

    @GetMapping("/compte-resultat")
    public CompteResultatDto compteResultat(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId,
                                             @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return etatsFinanciersService.genererCompteResultat(entrepriseId, exerciceId, principal);
    }
}
