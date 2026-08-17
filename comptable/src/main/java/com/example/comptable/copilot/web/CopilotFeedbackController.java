package com.example.comptable.copilot.web;

import com.example.comptable.copilot.CopilotFeedbackService;
import com.example.comptable.copilot.web.dto.CopilotFeedbackRequest;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Mission P1.9 (section 30) : feedback 👍/👎 sur une reponse du Copilot - accessible depuis
 * n'importe quelle portee (comptable/cabinet/dirigeant), meme perimetre de roles que
 * {@code /copilot-dirigeant/**} puisque le dirigeant (CLIENT) doit pouvoir laisser un feedback
 * sur ses propres reponses. */
@RestController
@RequestMapping("/api/copilot/queries/{queryId}/feedback")
@RequiredArgsConstructor
public class CopilotFeedbackController {

    private final CopilotFeedbackService copilotFeedbackService;

    @PostMapping
    public ResponseEntity<Void> enregistrer(@PathVariable UUID queryId,
                                             @RequestBody CopilotFeedbackRequest requete,
                                             @AuthenticationPrincipal UtilisateurPrincipal principal) {
        copilotFeedbackService.enregistrerFeedback(queryId, requete.feedback(), requete.reason(), principal);
        return ResponseEntity.noContent().build();
    }
}
