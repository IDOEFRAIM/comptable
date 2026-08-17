package com.example.comptable.copilot.domain;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mission P1.9 (section 8/9/13/14 "Copilot Usage/Quality") : seule nouvelle table du chantier -
 * c'est la seule donnee de la mission sans aucune source existante a reutiliser (le Copilot
 * est strictement en lecture, {@code CopilotService}/{@code CabinetCopilotService} n'ecrivaient
 * jamais rien avant ce chantier). Append-only : une ligne par question, mise a jour uniquement
 * pour y accrocher un feedback ulterieur (jamais de suppression). Contenu volontairement reduit
 * (section 54 "preferer intent/metadata/aggregates au contenu complet") : la question brute est
 * conservee (necessaire pour le feedback qualitatif) mais aucune preuve/document complet n'est
 * jamais stocke ici, seulement leur nombre.
 */
@Entity
@Table(name = "copilot_query")
@Getter
@Setter
@NoArgsConstructor
public class CopilotQuery extends UuidEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "entreprise_id")
    private UUID entrepriseId;

    @Column(name = "utilisateur_id")
    private UUID utilisateurId;

    @Column(nullable = false)
    private String portee;

    @Column(nullable = false)
    private String question;

    @Column(nullable = false)
    private String intent;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Column(nullable = false)
    private boolean fallback;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** POSITIVE/NEGATIVE, jamais renseigne a la creation - mission section 30. */
    private String feedback;

    @Column(name = "feedback_reason")
    private String feedbackReason;

    @Column(name = "feedback_at")
    private OffsetDateTime feedbackAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
