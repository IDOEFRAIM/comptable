package com.example.comptable.agent.whatsapp.domain;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Marqueur d'idempotence : un message WhatsApp (identifie par son message_id Meta) n'est
 * traite qu'une seule fois, meme si le webhook est livre plusieurs fois pour le meme evenement.
 */
@Entity
@Table(name = "whatsapp_evenement_traite")
@Getter
@Setter
@NoArgsConstructor
public class WhatsappEvenementTraite extends UuidEntity {

    @Column(name = "message_id", nullable = false, unique = true)
    private String messageId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public WhatsappEvenementTraite(String messageId) {
        this.messageId = messageId;
    }

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
