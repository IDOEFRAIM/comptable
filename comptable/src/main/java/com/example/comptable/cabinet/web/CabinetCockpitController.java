package com.example.comptable.cabinet.web;

import com.example.comptable.cabinet.CabinetCockpitService;
import com.example.comptable.cabinet.web.dto.CabinetCockpitDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mission P1 (section 8) : point d'entree unique du cabinet, distinct des routes
 * {@code /api/entreprises/{id}/...} par entreprise (P0) - jamais de {@code entrepriseId} en
 * chemin ici, le perimetre est le tenant du principal connecte. Restreint a
 * COLLABORATEUR/EXPERT_COMPTABLE au niveau {@code SecurityConfig} : un CLIENT n'a jamais ce role. */
@RestController
@RequestMapping("/api/cabinet")
@RequiredArgsConstructor
public class CabinetCockpitController {

    private final CabinetCockpitService cabinetCockpitService;

    @GetMapping("/cockpit")
    public CabinetCockpitDto cockpit(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return cabinetCockpitService.generer(principal);
    }
}
