package com.example.comptable.copilot.web;

import com.example.comptable.copilot.CabinetCopilotService;
import com.example.comptable.copilot.web.dto.CopilotQuestionRequest;
import com.example.comptable.copilot.web.dto.CopilotResponseDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Copilot niveau cabinet (mission P1, section 40) : lecture seule, perimetre cross-entreprises. */
@RestController
@RequestMapping("/api/cabinet/copilot")
@RequiredArgsConstructor
public class CabinetCopilotController {

    private final CabinetCopilotService cabinetCopilotService;

    @PostMapping("/question")
    public CopilotResponseDto question(@RequestBody CopilotQuestionRequest requete,
                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return cabinetCopilotService.repondre(requete.question(), principal);
    }
}
