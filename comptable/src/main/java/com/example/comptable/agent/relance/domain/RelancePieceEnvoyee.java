package com.example.comptable.agent.relance.domain;

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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Trace d'une tentative de relance WhatsApp pour une {@code DemandePiece} en retard. UUID brut
 * vers demande_piece/entreprise/utilisateur (pas de relation JPA) : meme convention que
 * {@code Document.ecritureId}/{@code DemandePiece.documentId}, pour ne pas coupler cet agregat
 * d'audit aux agregats metier qu'il ne fait qu'observer.
 */
@Entity
@Table(name = "relance_piece_envoyee")
@Getter
@Setter
@NoArgsConstructor
public class RelancePieceEnvoyee extends UuidEntity {

    @Column(name = "demande_piece_id", nullable = false)
    private UUID demandePieceId;

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Column(name = "utilisateur_client_id", nullable = false)
    private UUID utilisateurClientId;

    @Column(nullable = false)
    private String telephone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutRelance statut;

    @Column(name = "whatsapp_message_id")
    private String whatsappMessageId;

    @Column(name = "motif_echec")
    private String motifEchec;

    @Column(name = "envoye_at", nullable = false)
    private OffsetDateTime envoyeAt;

    @PrePersist
    private void onCreate() {
        if (envoyeAt == null) {
            envoyeAt = OffsetDateTime.now();
        }
    }
}
