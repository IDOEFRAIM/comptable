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

/** Copilot Evidence-First niveau comptable (mission P1, section 14). Lecture seule : aucune
 * methode ici ne peut creer/modifier/supprimer quoi que ce soit (section 16). */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/copilot")
@RequiredArgsConstructor
public class CopilotController {

    private final CopilotService copilotService;

    @PostMapping("/question")
    public CopilotResponseDto question(@PathVariable UUID entrepriseId,
                                        @RequestBody CopilotQuestionRequest requete,
                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return copilotService.repondre(entrepriseId, requete.question(), principal);
    }
}
