package com.example.comptable.banque;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Rapprochement d'une transaction bancaire avec une ligne d'ecriture de tresorerie (le reglement cote banque) : tracable (qui, quand,
 * pourquoi, avec quelle confiance), jamais supprime, annulable ({@code REVERSED}). La suite du pipeline (reglement -&gt; allocation -&gt; factures)
 * reste portee par l'ecriture comptable : une transaction n'est JAMAIS liee directement a une facture.
 */
@Entity
@Table(name = "bank_reconciliation")
@Getter
@Setter
@NoArgsConstructor
public class BankReconciliation extends UuidEntity {

    public static final String CIBLE_LIGNE_ECRITURE = "LIGNE_ECRITURE";

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Column(name = "bank_transaction_id", nullable = false)
    private UUID bankTransactionId;

    @Column(name = "target_type", nullable = false)
    private String targetType = CIBLE_LIGNE_ECRITURE;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DecisionReconciliation decision;

    /** 0 a 100 : somme des criteres explicables (voir {@code RapprochementClassifier}). */
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceReconciliation source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutReconciliation status;

    /** Pourquoi ce candidat : criteres et points, texte lisible. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation_json")
    private Map<String, Object> explanation;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "rejected_by")
    private UUID rejectedBy;

    @Column(name = "rejected_at")
    private OffsetDateTime rejectedAt;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    @Column(name = "reversed_at")
    private OffsetDateTime reversedAt;

    @Column(name = "reversal_reason")
    private String reversalReason;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
