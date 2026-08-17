package com.example.comptable.copilot;

import com.example.comptable.copilot.domain.CopilotQuery;
import com.example.comptable.copilot.repository.CopilotQueryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Mission P1.9 (section 8/9/13/14) : enregistre chaque question posee au Copilot pour les KPI
 * d'usage/qualite - jamais critique au chemin de reponse (une panne de telemetrie ne doit jamais
 * empecher le Copilot de repondre, meme principe que "si ai-service est indisponible, le Core
 * reste fonctionnel"). Transaction propre ({@code REQUIRES_NEW}) : les methodes appelantes
 * ({@code CopilotService.repondre}...) sont {@code @Transactional(readOnly = true)}, une
 * ecriture dans cette meme transaction serait rejetee par certains pilotes JDBC en lecture seule.
 */
@Component
@RequiredArgsConstructor
public class CopilotTelemetryRecorder {

    private static final Logger log = LoggerFactory.getLogger(CopilotTelemetryRecorder.class);
    private static final int LONGUEUR_MAX_QUESTION = 500;

    private final CopilotQueryRepository copilotQueryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID enregistrer(UUID tenantId, UUID entrepriseId, UUID utilisateurId, String portee,
                             String question, String intent, double confidence, int evidenceCount,
                             boolean fallback, long latencyMs) {
        try {
            CopilotQuery q = new CopilotQuery();
            q.setTenantId(tenantId);
            q.setEntrepriseId(entrepriseId);
            q.setUtilisateurId(utilisateurId);
            q.setPortee(portee);
            q.setQuestion(tronquer(question));
            q.setIntent(intent);
            q.setConfidence(confidence);
            q.setEvidenceCount(evidenceCount);
            q.setFallback(fallback);
            q.setLatencyMs(latencyMs);
            return copilotQueryRepository.save(q).getId();
        } catch (RuntimeException e) {
            log.warn("Copilot : echec de l'enregistrement de la telemetrie (reponse deja envoyee, sans impact) : {}",
                    e.getMessage());
            return null;
        }
    }

    private String tronquer(String question) {
        if (question == null) {
            return "";
        }
        return question.length() > LONGUEUR_MAX_QUESTION ? question.substring(0, LONGUEUR_MAX_QUESTION) : question;
    }

    static int compterPreuves(List<?> evidence) {
        return evidence == null ? 0 : evidence.size();
    }
}
