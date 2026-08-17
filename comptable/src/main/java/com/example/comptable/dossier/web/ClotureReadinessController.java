package com.example.comptable.dossier.web;

import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/cloture-readiness")
@RequiredArgsConstructor
public class ClotureReadinessController {

    private final ClotureReadinessService clotureReadinessService;

    @GetMapping
    public ClotureReadinessDto obtenir(@PathVariable UUID entrepriseId,
                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return clotureReadinessService.evaluer(entrepriseId, principal);
    }
}
