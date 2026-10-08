package com.example.comptable.collecte.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** Lien demande -&gt; message : un message regroupe plusieurs demandes, chaque demande garde l'historique de ses messages. */
@Entity
@Table(name = "demande_piece_message")
@Getter
@Setter
@NoArgsConstructor
public class DemandeMessage {

    @EmbeddedId
    private Cle id;

    public DemandeMessage(UUID demandeId, UUID messageId) {
        this.id = new Cle(demandeId, messageId);
    }

    public UUID getDemandeId() {
        return id.demandeId;
    }

    public UUID getMessageId() {
        return id.messageId;
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @lombok.EqualsAndHashCode
    public static class Cle implements Serializable {
        @Column(name = "demande_id")
        private UUID demandeId;
        @Column(name = "message_id")
        private UUID messageId;

        public Cle(UUID demandeId, UUID messageId) {
            this.demandeId = demandeId;
            this.messageId = messageId;
        }
    }
}
