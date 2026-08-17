package com.example.comptable.agent.matching.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.identite.domain.Utilisateur;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Trace d'une proposition du Universal Matching Engine (ai-service) pour une ligne de releve
 * bancaire (ou, plus tard, toute autre source). Distincte du pointage definitif
 * ({@code LigneReleve.ligneEcritureId}) : une proposition n'affecte jamais la comptabilite
 * elle-meme tant qu'elle n'est pas passee a {@code ACCEPTED} (cf. MatchDecisionEngine) - c'est
 * la piste d'audit qui permettra un jour de mesurer le taux d'acceptation/faux positifs du
 * modele (section 10/17 de la mission).
 */
@Entity
@Table(name = "ai_match_proposal")
@Getter
@Setter
@NoArgsConstructor
public class AiMatchProposal extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    /** Identifiant de la source sous forme texte (pas UUID strict) : une source peut un jour
     * provenir d'un systeme externe dont l'identifiant n'est pas un UUID Postgres. */
    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_type")
    private SourceType candidateType;

    @Column(name = "candidate_id")
    private String candidateId;

    @Column(nullable = false)
    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DecisionRapprochementIA decision;

    /** matchType brut renvoye par ai-service (ONE_TO_ONE, PARTIAL_PAYMENT...) : simple donnee
     * d'affichage/audit, pas de branchement logique Java dessus, donc pas d'enum dedie. */
    @Column(name = "match_type")
    private String matchType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons_json")
    private List<String> reasonsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings_json")
    private List<String> warningsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutPropositionIA status = StatutPropositionIA.PROPOSED;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "validated_at")
    private OffsetDateTime validatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by_id")
    private Utilisateur validatedBy;

    /** Mission P1.9 (section 9 "False Auto-Match") : motif libre du rejet humain, jamais rempli
     * pour un rejet automatique. Alimente le taux de correction/rejet par cause. */
    @Column(name = "rejection_reason")
    private String rejectionReason;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
