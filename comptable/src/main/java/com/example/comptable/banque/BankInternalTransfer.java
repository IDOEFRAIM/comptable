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
 * Virement interne entre deux comptes bancaires de l'entreprise : la sortie du compte A et l'entree du compte B ne sont jamais interpretees
 * separement (ni charge, ni produit, ni reglement de tiers). Tracable, jamais supprime, annulable ; un rejet ou une annulation sont memorises.
 */
@Entity
@Table(name = "bank_internal_transfer")
@Getter
@Setter
@NoArgsConstructor
public class BankInternalTransfer extends UuidEntity {

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Column(name = "debit_transaction_id", nullable = false)
    private UUID debitTransactionId;

    @Column(name = "credit_transaction_id", nullable = false)
    private UUID creditTransactionId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DecisionReconciliation decision;

    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceReconciliation source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutReconciliation status;

    /** Ecriture comptable de transfert DEJA existante (jamais generee ici) ; null tant qu'aucune n'a ete trouvee. */
    @Column(name = "ecriture_id")
    private UUID ecritureId;

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
