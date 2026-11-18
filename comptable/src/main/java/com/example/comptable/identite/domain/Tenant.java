package com.example.comptable.identite.domain;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
public class Tenant extends UuidEntity {

    @Column(name = "raison_sociale", nullable = false)
    private String raisonSociale;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false, length = 2)
    private String pays;

    @Column(name = "plan_abonnement", nullable = false)
    private String planAbonnement = "ESSAI";

    @Column(nullable = false)
    private String statut = "ACTIF";

    /** Fin de la période d'essai (plan ESSAI). Null : pas de date. Une date dépassée est signalée à l'administrateur, jamais appliquée automatiquement. */
    @Column(name = "fin_essai")
    private LocalDate finEssai;

    /** Nombre maximal de membres de cabinet actifs (experts et collaborateurs). Null : illimité. */
    @Column(name = "quota_utilisateurs")
    private Integer quotaUtilisateurs;

    /** Nombre maximal de dossiers (entreprises) actifs. Null : illimité. */
    @Column(name = "quota_dossiers")
    private Integer quotaDossiers;

    @Column(name = "suspendu_motif", length = 500)
    private String suspenduMotif;

    @Column(name = "suspendu_le")
    private OffsetDateTime suspenduLe;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
