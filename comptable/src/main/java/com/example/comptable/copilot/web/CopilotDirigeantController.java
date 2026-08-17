package com.example.comptable.copilot.web;

import com.example.comptable.copilot.CopilotService;
import com.example.comptable.copilot.web.dto.CopilotQuestionRequest;
import com.example.comptable.copilot.web.dto.CopilotResponseDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Copilot Dirigeant (mission P1.5, section 46) : meme service, meme isolation d'acces que
 * {@link CopilotController} - endpoint distinct uniquement pour ouvrir l'acces au role CLIENT
 * (le dirigeant) sans toucher au perimetre COLLABORATEUR/EXPERT_COMPTABLE existant. */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/copilot-dirigeant")
@RequiredArgsConstructor
public class CopilotDirigeantController {

    private final CopilotService copilotService;

    @PostMapping("/question")
    public CopilotResponseDto question(@PathVariable UUID entrepriseId,
                                        @RequestBody CopilotQuestionRequest requete,
                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return copilotService.repondrePourDirigeant(entrepriseId, requete.question(), principal);
    }
}
