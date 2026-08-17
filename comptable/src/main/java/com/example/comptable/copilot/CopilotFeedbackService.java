package com.example.comptable.copilot;

import com.example.comptable.copilot.domain.CopilotQuery;
import com.example.comptable.copilot.repository.CopilotQueryRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** Mission P1.9 (section 30) : 👍/👎 sur une reponse du Copilot, avec motif optionnel en cas de
 * retour negatif (section 31). Isolation tenant meme convention que partout ailleurs (404, pas
 * 403, pour ne pas confirmer l'existence d'un identifiant d'une autre organisation). */
@Service
@RequiredArgsConstructor
public class CopilotFeedbackService {

    private static final Set<String> FEEDBACKS_VALIDES = Set.of("POSITIVE", "NEGATIVE");

    private final CopilotQueryRepository copilotQueryRepository;

    @Transactional
    public void enregistrerFeedback(UUID queryId, String feedback, String reason, UtilisateurPrincipal principal) {
        if (feedback != null && !FEEDBACKS_VALIDES.contains(feedback)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feedback doit etre POSITIVE ou NEGATIVE");
        }
        CopilotQuery query = copilotQueryRepository.findById(queryId)
                .filter(q -> q.getTenantId().equals(principal.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question Copilot introuvable"));
        query.setFeedback(feedback);
        query.setFeedbackReason(reason);
        query.setFeedbackAt(OffsetDateTime.now());
        copilotQueryRepository.save(query);
    }
}
