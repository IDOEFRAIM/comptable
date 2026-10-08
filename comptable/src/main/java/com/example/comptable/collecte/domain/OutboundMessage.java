package com.example.comptable.collecte.domain;

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

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Message sortant en boite d'envoi (outbox) : ecrit dans la transaction qui cree la demande, envoye ensuite par un worker
 * (jamais pendant une transaction comptable). Le fournisseur n'est appele que par le worker.
 *
 * <p>{@code attemptCount} / {@code nextAttemptAt} portent le RETRY TECHNIQUE (le message n'est pas parti) ; une RELANCE
 * CLIENT est un NOUVEAU message de type {@link TypeMessage#REMINDER}.</p>
 */
@Entity
@Table(name = "outbound_message")
@Getter
@Setter
@NoArgsConstructor
public class OutboundMessage extends UuidEntity {

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CanalMessage channel;

    @Column(nullable = false)
    private String recipient;

    @Column(name = "recipient_name")
    private String recipientName;

    @Column(name = "template_code", nullable = false)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeMessage kind;

    /** Contenu structure (destinataire, entreprise, elements demandes, lien de depot) : le texte est rendu a l'envoi. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutMessage status = StatutMessage.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "last_error")
    private String lastError;

    /** RETRYABLE (panne technique) ou PERMANENT (destinataire/template refuse) pour la derniere erreur. */
    @Column(name = "error_kind")
    private String errorKind;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "failed_at")
    private OffsetDateTime failedAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = createdAt;
        }
    }
}
