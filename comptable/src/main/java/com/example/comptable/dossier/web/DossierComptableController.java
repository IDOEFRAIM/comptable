package com.example.comptable.dossier.web;

import com.example.comptable.dossier.DossierComptableService;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Point d'entree P0 : "Dossier Comptable Intelligent" (section 8 de la mission). */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/dossier")
@RequiredArgsConstructor
public class DossierComptableController {

    private final DossierComptableService dossierComptableService;

    @GetMapping
    public DossierComptableDto obtenir(@PathVariable UUID entrepriseId,
                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return dossierComptableService.generer(entrepriseId, principal);
    }
}
