package com.example.comptable.demande.domain;

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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Demande ciblee d'une piece manquante adressee par le cabinet a une entreprise cliente.
 * Le lien vers le document deposera (documentId) reste une simple UUID, pas une relation
 * JPA, pour eviter tout couplage/proxy lazy entre les deux agregats (meme convention que
 * Document.ecritureId).
 */
@Entity
@Table(name = "demande_piece")
@Getter
@Setter
@NoArgsConstructor
public class DemandePiece extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @Column(nullable = false)
    private String libelle;

    private String description;

    @Column(name = "date_limite")
    private LocalDate dateLimite;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutDemandePiece statut = StatutDemandePiece.EN_ATTENTE;

    @Column(name = "document_id")
    private UUID documentId;

    /** Finding a l'origine de cette demande (mission P0, boucle de resolution WhatsApp) - simple
     * UUID, pas une relation JPA, meme convention que {@link #documentId} : evite tout couplage
     * entre les modules {@code demande} et {@code agent.finding}. Null pour une demande creee
     * manuellement (hors P0) ou par l'agent autonome (cf. {@code DemandePieceService#creerDepuisAgent}). */
    @Column(name = "finding_id")
    private UUID findingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demandee_par_id")
    private Utilisateur demandeePar;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolue_at")
    private OffsetDateTime resolueAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
